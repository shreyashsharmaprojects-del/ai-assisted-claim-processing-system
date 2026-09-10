/**
 * V2-4/V2-5/V2-6 acceptance: the staged workflow over real HTTP against real
 * Postgres. Review guards, verification discipline, assessment limit-chain,
 * partial approval with payment = Σ net, above-authority proposals that do NOT move
 * the claim, named/auto/supervisor-fallback referral, escalated-handler close,
 * NEED_INFO round-trip, and the claimant outcome wall.
 */
package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StagedWorkflowIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----StagedWorkflowBoundary1";

    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String SUB_L3 = "10000000-0000-0000-0000-000000000007";
    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";
    private static final String CLAIMANT = "sub-claimant-staged";

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    // --- the happy path: review -> verify -> assess -> partial approve -------------

    @Test
    void happyPathPartialApprovalClosesWithPaymentEqualToSumOfNets() throws Exception {
        String claimNumber = fileCoverFnol(
                "[{\"coverCode\":\"HOSPITALIZATION\",\"claimedAmount\":200000},"
                        + "{\"coverCode\":\"OPD\",\"claimedAmount\":40000}]");
        String bearer = holderBearer(claimNumber);

        // Stage starts at REVIEW; the staged view carries the authority context.
        String staged = get("/api/claims/" + claimNumber + "/staged", bearer).body();
        assertTrue(staged.contains("\"stage\":\"REVIEW\""), staged);
        assertTrue(staged.contains("\"authorityLimit\":100000"), staged);
        assertTrue(staged.contains("\"authorityBasis\":\"APPROVED_TOTAL\""), staged);

        // Decision before the DECISION stage is rejected.
        assertEquals(400, postJson("/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"too early\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":1000}]}").statusCode());

        // ADVANCE REVIEW -> VERIFICATION opens the default checklist rows.
        HttpResponse<String> advanced = postJson("/api/claims/" + claimNumber + "/review",
                bearer,
                "{\"action\":\"ADVANCE\",\"rationale\":\"Looks consistent; verifying.\"}");
        assertEquals(200, advanced.statusCode(), advanced.body());
        assertTrue(advanced.body().contains("\"stage\":\"VERIFICATION\""), advanced.body());
        assertEquals(3, count("SELECT count(*) FROM verification WHERE claim_id = ?",
                idOf(claimNumber)));
        assertEquals(1, count("SELECT count(*) FROM verification WHERE claim_id = ? "
                + "AND type = 'PHYSICAL'", idOf(claimNumber)));
        assertEquals(1, count("SELECT count(*) FROM verification WHERE claim_id = ? "
                + "AND type = 'DOCUMENT'", idOf(claimNumber)));
        assertEquals(1, count("SELECT count(*) FROM verification WHERE claim_id = ? "
                + "AND type = 'CLAUSE'", idOf(claimNumber)));

        // Assessment before the whole checklist is COMPLETE is rejected.
        assertEquals(400, putJson("/api/claims/" + claimNumber + "/assessment", bearer,
                "{\"rationale\":\"x\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"assessedAmount\":1000},"
                        + "{\"coverCode\":\"OPD\",\"assessedAmount\":1000}]}").statusCode());

        // Complete the whole default checklist (outcome + notes required each).
        // The follow-up DIGITAL row below must be COMPLETE too — every open row
        // gates assessment.
        HttpResponse<String> created = postJson(
                "/api/claims/" + claimNumber + "/verifications", bearer,
                "{\"type\":\"DIGITAL\",\"notes\":\"Checking hospital records.\"}");
        assertEquals(200, created.statusCode(), created.body());
        long verificationId = idOfVerification(created.body());
        assertEquals(400, putJson(
                "/api/claims/" + claimNumber + "/verifications/" + verificationId, bearer,
                "{\"status\":\"COMPLETE\",\"notes\":\"No outcome given.\"}").statusCode(),
                "COMPLETE without an outcome is rejected");
        HttpResponse<String> completed = putJson(
                "/api/claims/" + claimNumber + "/verifications/" + verificationId, bearer,
                "{\"status\":\"COMPLETE\",\"outcome\":\"PASSED\","
                        + "\"notes\":\"Dates and amounts match.\","
                        + "\"evidenceRefs\":\"bill.pdf\"}");
        assertEquals(200, completed.statusCode(), completed.body());
        completeAllVerifications(claimNumber, bearer);

        // Assessment: OPD assessed 40000 > 30000 sub-limit -> 400, claim stays open.
        HttpResponse<String> overLimit = putJson(
                "/api/claims/" + claimNumber + "/assessment", bearer,
                "{\"rationale\":\"Trying over-limit.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"assessedAmount\":180000},"
                        + "{\"coverCode\":\"OPD\",\"assessedAmount\":40000}]}");
        assertEquals(400, overLimit.statusCode(), overLimit.body());
        assertTrue(overLimit.body().contains("sub-limit"), overLimit.body());
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals("VERIFICATION", stageOf(claimNumber));

        // Valid assessment moves VERIFICATION -> DECISION.
        HttpResponse<String> assessed = putJson(
                "/api/claims/" + claimNumber + "/assessment", bearer,
                "{\"rationale\":\"Bills verified.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"assessedAmount\":180000},"
                        + "{\"coverCode\":\"OPD\",\"assessedAmount\":25000}]}");
        assertEquals(200, assessed.statusCode(), assessed.body());
        assertTrue(assessed.body().contains("\"stage\":\"DECISION\""), assessed.body());

        // Mixed decision within L1 authority (HLTH-PLUS L1 100000; approved 80000):
        // PARTIALLY_APPROVED with payment = Σ net = (80000-10000) + 0 = 70000.
        HttpResponse<String> decided = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"Within authority to close now.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":80000,\"remarks\":\"Bills verified.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Pre-dates the waiting period.\","
                        + "\"denialReason\":\"EXCLUDED_PER_CLAUSE\"}]}");
        assertEquals(200, decided.statusCode(), decided.body());
        assertTrue(decided.body().contains("\"status\":\"CLOSED\""), decided.body());

        Long claimId = idOf(claimNumber);
        assertEquals("CLOSED", statusOf(claimNumber));
        assertEquals("PARTIALLY_APPROVED", decisionOf(claimNumber));
        assertEquals(0, new java.math.BigDecimal("70000").compareTo(
                jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE claim_id = ?",
                        java.math.BigDecimal.class, claimId)),
                "payment = Σ net payable (80000-10000 deductible, rejected OPD pays 0)");
        assertEquals(1, count("SELECT count(*) FROM payment WHERE claim_id = ?", claimId));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'DECISION' "
                + "AND entity_id = ?", claimId));
        // Decision email fires on closure (outbox row in-transaction + flushed send).
        assertEquals(1, count("SELECT count(*) FROM email_outbox WHERE claim_id = ? "
                + "AND kind = 'DECISION'", claimId));

        // Claimant view: per-cover outcomes + aggregate + payable figure, wall intact.
        String claimantView = get("/api/claims/" + claimNumber, claimantBearer()).body();
        assertTrue(claimantView.contains("\"decision\":\"PARTIALLY_APPROVED\""), claimantView);
        assertTrue(claimantView.contains("\"netPayableTotal\":70000"), claimantView);
        assertTrue(claimantView.contains("\"coverCode\":\"HOSPITALIZATION\""), claimantView);
        assertTrue(claimantView.contains("\"decision\":\"APPROVED\""), claimantView);
        assertTrue(claimantView.contains("\"approvedAmount\":80000"), claimantView);
        assertTrue(claimantView.contains("\"decision\":\"REJECTED\""), claimantView);
        for (String internal : new String[] {"assessedAmount", "deductibleAmount",
                "adjustmentAmount", "reserveAmount", "assignedTo", "performedBy",
                "proposalsSaved", "proposedTotal", "authorityLimit"}) {
            assertFalse(claimantView.contains("\"" + internal + "\""),
                    "claimant view must not contain " + internal + ": " + claimantView);
        }
    }

    // --- review guards ------------------------------------------------------------------

    @Test
    void reviewRejectClosesDeniedAndAdvanceRequiresReviewStage() throws Exception {
        String claimNumber = fileCoverFnol(
                "[{\"coverCode\":\"OPD\",\"claimedAmount\":5000}]");
        String bearer = holderBearer(claimNumber);

        HttpResponse<String> rejected = postJson("/api/claims/" + claimNumber + "/review",
                bearer, "{\"action\":\"REJECT\",\"rationale\":\"Excluded by clauses.\"}");
        assertEquals(200, rejected.statusCode(), rejected.body());
        assertEquals("CLOSED", statusOf(claimNumber));
        assertEquals("DENIED", decisionOf(claimNumber));
        assertEquals(0, count("SELECT count(*) FROM payment WHERE claim_id = ?",
                idOf(claimNumber)));

        // A second review on the closed claim is rejected.
        assertEquals(400, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"ADVANCE\",\"rationale\":\"x\"}").statusCode());
    }

    @Test
    void invalidReviewActionsAreRejected() throws Exception {
        String claimNumber = fileCoverFnol(
                "[{\"coverCode\":\"OPD\",\"claimedAmount\":5000}]");
        String bearer = holderBearer(claimNumber);

        assertEquals(400, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"MAYBE\",\"rationale\":\"x\"}").statusCode());
        assertEquals(400, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"ADVANCE\"}").statusCode(),
                "ADVANCE needs a rationale");
    }

    // --- NEED_INFO round-trip -----------------------------------------------------------------

    @Test
    void needInfoFromVerificationAndClaimantResponseReturnsToPriorStage()
            throws Exception {
        String claimNumber = driveToVerification();
        String bearer = holderBearer(claimNumber);

        HttpResponse<String> parked = postJson("/api/claims/" + claimNumber + "/review",
                bearer,
                "{\"action\":\"NEED_INFO\",\"requestedItems\":\"Upload the bill.\"}");
        assertEquals(200, parked.statusCode(), parked.body());
        assertTrue(parked.body().contains("\"status\":\"NEED_INFO\""), parked.body());
        assertTrue(parked.body().contains("\"needInfoPriorStage\":\"VERIFICATION\""),
                parked.body());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT assigned_adjuster_id FROM claim WHERE claim_number = ?",
                Long.class, claimNumber),
                "NEED_INFO leaves the assignee bucket");

        // The parked claim is out of the adjuster's hands.
        assertEquals(404,
                get("/api/claims/" + claimNumber + "/staged", bearer).statusCode());

        // NEED_INFO from DECISION is allowed too (was REVIEW/VERIFICATION only).
        String decided = driveToDecision();
        HttpResponse<String> parkedFromDecision = postJson(
                "/api/claims/" + decided + "/review", holderBearer(decided),
                "{\"action\":\"NEED_INFO\",\"requestedItems\":\"Confirm the bill total.\"}");
        assertEquals(200, parkedFromDecision.statusCode(), parkedFromDecision.body());
        assertTrue(parkedFromDecision.body().contains("\"status\":\"NEED_INFO\""),
                parkedFromDecision.body());
        assertTrue(parkedFromDecision.body().contains("\"needInfoPriorStage\":\"DECISION\""),
                parkedFromDecision.body());

        // Claimant responds: back UNDER_REVIEW at VERIFICATION, reassigned.
        HttpResponse<String> response = postJson(
                "/api/claims/" + claimNumber + "/need-info-response", claimantBearer(),
                "{\"message\":\"Uploaded the bill.\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"needInfoReason\":null")
                || !response.body().contains("needInfoReason"),
                "the answered request text clears off the claimant view: "
                        + response.body());
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals("VERIFICATION", stageOf(claimNumber));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT assigned_adjuster_id FROM claim WHERE claim_number = ?",
                Long.class, claimNumber),
                "the response reassigns the claim");
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = "
                + "'NEED_INFO_RESPONDED' AND entity_id = ?", idOf(claimNumber)));

        // A second response is rejected (no longer NEED_INFO).
        assertEquals(400, postJson("/api/claims/" + claimNumber + "/need-info-response",
                claimantBearer(), "{\"message\":\"Again.\"}").statusCode());

        // Someone else's NEED_INFO claim is a 404, never a 403.
        assertEquals(404, postJson("/api/claims/" + claimNumber + "/need-info-response",
                JwtTestConfig.tokenFor("sub-claimant-other", "claimant"),
                "{\"message\":\"Mine?\"}").statusCode());
    }

    // --- above-authority proposals + referral ----------------------------------------------------

    @Test
    void aboveAuthoritySavesProposalsWithoutMovingTheClaim() throws Exception {
        String claimNumber = driveToDecision();
        String bearer = holderBearer(claimNumber);

        // HLTH-PLUS L1 100000; propose 150000 approved -> proposals saved, claim stays.
        HttpResponse<String> gated = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"Needs senior sign-off here.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":150000,\"remarks\":\"Large bill.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Waiting period.\","
                        + "\"denialReason\":\"EXCLUDED_PER_CLAUSE\"}]}");
        assertEquals(200, gated.statusCode(), gated.body());
        assertTrue(gated.body().contains("\"proposalsSaved\":true"), gated.body());
        assertTrue(gated.body().contains("\"proposedTotal\":150000"), gated.body());
        assertTrue(gated.body().contains("\"authorityLimit\":100000"), gated.body());

        Long claimId = idOf(claimNumber);
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals("DECISION", stageOf(claimNumber));
        assertEquals(0, count("SELECT count(*) FROM payment WHERE claim_id = ?", claimId),
                "no payment on a gated proposal");
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        // Same assignee still holds it (nothing moved by itself).
        assertEquals(bearerSub(bearer), assigneeSubOf(claimNumber));
        assertEquals(1, count("SELECT count(*) FROM claim_cover WHERE claim_id = ? "
                + "AND is_proposal = TRUE", claimId));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = "
                + "'PROPOSALS_SAVED' AND entity_id = ?", claimId));

        // Referring with no proposals is a 400 — use a fresh claim driven to DECISION.
        String fresh = driveToDecision();
        assertEquals(400, postJson("/api/claims/" + fresh + "/refer",
                holderBearer(fresh), "{\"auto\":true,\"reason\":\"Nothing saved.\"}")
                .statusCode());
    }

    @Test
    void referAutoPicksLowestCoveringRungAndOriginalActorLosesAccess() throws Exception {
        String claimNumber = driveToDecision();
        String actorBearer = holderBearer(claimNumber);
        proposeAboveL1(claimNumber, actorBearer);

        HttpResponse<String> referred = postJson("/api/claims/" + claimNumber + "/refer",
                actorBearer, "{\"auto\":true,\"reason\":\"Above my authority.\"}");
        assertEquals(200, referred.statusCode(), referred.body());
        // HLTH-PLUS 150000 clears L1 (100000), fits L2 (400000): lands on L2.
        assertTrue(referred.body().contains("\"escalatedTo\":\"L2\""), referred.body());
        assertEquals("DECISION", stageOf(claimNumber),
                "referral preserves the stage");
        assertEquals(1, count("SELECT count(*) FROM claim_cover WHERE claim_id = ? "
                + "AND is_proposal = TRUE", idOf(claimNumber)),
                "proposals travel untouched");

        // The original actor lost access (404 on staged + decision + refer).
        assertEquals(404,
                get("/api/claims/" + claimNumber + "/staged", actorBearer).statusCode());
        assertEquals(404, postJson("/api/claims/" + claimNumber + "/cover-decision",
                actorBearer,
                "{\"rationale\":\"x\",\"expectedVersion\":" + versionOf(claimNumber)
                        + ",\"covers\":[]}").statusCode());

        // The L2 holder sees the proposals and may modify + close within authority.
        String holderBearer = holderBearer(claimNumber);
        String staged = get("/api/claims/" + claimNumber + "/staged", holderBearer).body();
        assertTrue(staged.contains("\"proposal\":true"), staged);
        HttpResponse<String> closed = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", holderBearer,
                "{\"rationale\":\"Revised within L2 authority.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":150000,\"remarks\":\"Agreed.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Waiting period.\","
                        + "\"denialReason\":\"EXCLUDED_PER_CLAUSE\"}]}");
        assertEquals(200, closed.statusCode(), closed.body());
        assertTrue(closed.body().contains("\"status\":\"CLOSED\""), closed.body());
        assertEquals("CLOSED", statusOf(claimNumber));
        assertEquals("PARTIALLY_APPROVED", decisionOf(claimNumber));
        assertEquals(0, new java.math.BigDecimal("140000").compareTo(
                jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE claim_id = ?",
                        java.math.BigDecimal.class, idOf(claimNumber))),
                "payment = 150000 - 10000 deductible");
    }

    @Test
    void referNamedSeniorRequiresHigherRungWithCoveringLimit() throws Exception {
        String claimNumber = driveToDecision();
        String actorBearer = holderBearer(claimNumber);
        proposeAboveL1(claimNumber, actorBearer);

        // Named L2 (Rahul, sub ...006): 150000 fits L2 400000 -> accepted.
        Long rahulId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE keycloak_sub = "
                        + "'10000000-0000-0000-0000-000000000006'",
                Long.class);
        HttpResponse<String> named = postJson("/api/claims/" + claimNumber + "/refer",
                actorBearer,
                "{\"targetAdjusterId\":" + rahulId + ",\"reason\":\"Named senior.\"}");
        assertEquals(200, named.statusCode(), named.body());
        assertTrue(named.body().contains("Rahul Singh"), named.body());

        // A twin claim: naming a same-rung L1 is a 400.
        String twin = driveToDecision();
        String twinBearer = holderBearer(twin);
        proposeAboveL1(twin, twinBearer);
        Long l1TwoId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE keycloak_sub = "
                        + "'10000000-0000-0000-0000-000000000002'",
                Long.class);
        assertEquals(400, postJson("/api/claims/" + twin + "/refer", twinBearer,
                "{\"targetAdjusterId\":" + l1TwoId + ",\"reason\":\"Same rung.\"}")
                .statusCode());
    }

    @Test
    void referFallsBackToSupervisorWhenNoRungCoversTheTotal() throws Exception {
        // No covering rung with a candidate: HLTH-PLUS claim proposing 150000
        // (above L1 100000) while L2/L3 adjusters are temporarily unprovisioned.
        // Auto-pick finds no rung with candidates -> ESCALATED_SUPERVISOR.
        String claimNumber = driveToDecision();
        String bearer = holderBearer(claimNumber);
        proposeAboveL1(claimNumber, bearer);
        deleteSeniorAdjusters();
        try {
            HttpResponse<String> referred = postJson("/api/claims/" + claimNumber
                    + "/refer", bearer,
                    "{\"auto\":true,\"reason\":\"Nobody above me is provisioned.\"}");
            assertEquals(200, referred.statusCode(), referred.body());
            assertTrue(referred.body().contains("\"escalatedTo\":\"SUPERVISOR\""),
                    referred.body());
            assertEquals("ESCALATED_SUPERVISOR", statusOf(claimNumber));
        } finally {
            restoreSeniorAdjusters();
        }
    }

    @Test
    void supervisorEscalatedCoverDecideGuardsAndL3Referral() throws Exception {
        // Guards: a non-escalated claim is a 400 on the supervisor endpoint.
        String claimNumber = driveToDecision();
        assertEquals(400, postJson("/api/claims/" + claimNumber + "/escalation-cover-decision",
                supervisorBearer(),
                "{\"rationale\":\"x\",\"expectedVersion\":" + versionOf(claimNumber)
                        + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":1000}]}").statusCode());

        // L1 holder proposes above L1; named L3 referral (skip-level L1->L3 allowed).
        String actorBearer = holderBearer(claimNumber);
        proposeAboveL1(claimNumber, actorBearer);
        Long meeraId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE keycloak_sub = '" + SUB_L3 + "'", Long.class);
        HttpResponse<String> toL3 = postJson("/api/claims/" + claimNumber + "/refer",
                actorBearer,
                "{\"targetAdjusterId\":" + meeraId + ",\"reason\":\"To L3.\"}");
        assertEquals(200, toL3.statusCode(), toL3.body());
        assertTrue(toL3.body().contains("\"escalatedTo\":\"L3\""), toL3.body());
        assertEquals("DECISION", stageOf(claimNumber),
                "referral preserves the stage");
        assertEquals(1, count("SELECT count(*) FROM claim_cover WHERE claim_id = ? "
                + "AND is_proposal = TRUE", idOf(claimNumber)),
                "proposals travel untouched");
    }

    @Test
    void supervisorCoverDecideOnEscalatedClaimClosesWithModifiedFigures() throws Exception {
        // ESCALATED_SUPERVISOR via the same provisioning-gap route as the fallback
        // test: the supervisor then modifies the figure and closes, ungated.
        String claimNumber = driveToDecision();
        String bearer = holderBearer(claimNumber);
        proposeAboveL1(claimNumber, bearer);
        deleteSeniorAdjusters();
        try {
            HttpResponse<String> referred = postJson("/api/claims/" + claimNumber
                    + "/refer", bearer,
                    "{\"auto\":true,\"reason\":\"Above every provisioned rung.\"}");
            assertEquals(200, referred.statusCode(), referred.body());
            assertTrue(referred.body().contains("\"escalatedTo\":\"SUPERVISOR\""),
                    referred.body());
        } finally {
            restoreSeniorAdjusters();
        }

        // The supervisor modifies the figure and closes (ungated). The fixture
        // claim carries HOSPITALIZATION + OPD: approve one, reject the other.
        HttpResponse<String> closed = postJson(
                "/api/claims/" + claimNumber + "/escalation-cover-decision",
                supervisorBearer(),
                "{\"rationale\":\"Agreed at revised figures now.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":120000,\"remarks\":\"Revised.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Waiting period.\","
                        + "\"denialReason\":\"EXCLUDED_PER_CLAUSE\"}]}");
        assertEquals(200, closed.statusCode(), closed.body());
        assertTrue(closed.body().contains("\"status\":\"CLOSED\""), closed.body());
        assertEquals("CLOSED", statusOf(claimNumber));
        assertEquals("PARTIALLY_APPROVED", decisionOf(claimNumber));
        assertEquals(0, new java.math.BigDecimal("110000").compareTo(
                jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE claim_id = ?",
                        java.math.BigDecimal.class, idOf(claimNumber))),
                "payment = 120000 - 10000 deductible");
        assertNull(jdbcTemplate.queryForObject(
                "SELECT authorized_by_id FROM payment WHERE claim_id = ?",
                Long.class, idOf(claimNumber)),
                "supervisor payments carry a NULL authorizer");

        // Claimant sees the outcome + payable figure, never the internals.
        String claimantView = get("/api/claims/" + claimNumber, claimantBearer()).body();
        assertTrue(claimantView.contains("\"decision\":\"PARTIALLY_APPROVED\""),
                claimantView);
        assertTrue(claimantView.contains("\"approvedAmount\":120000"), claimantView);
        assertTrue(claimantView.contains("\"netPayableTotal\":110000"), claimantView);
        assertFalse(claimantView.contains("assessedAmount"), claimantView);
    }

    // --- supervisor reassign preserves stage/history/proposals --------------------------

    @Test
    void supervisorReassignPreservesStageVerificationAndProposals() throws Exception {
        String claimNumber = driveToDecision();
        String bearer = holderBearer(claimNumber);
        proposeAboveL1(claimNumber, bearer);

        HttpResponse<String> reassigned = postJson(
                "/api/claims/" + claimNumber + "/reassign", supervisorBearer(),
                "{\"level\":\"L2\"}");
        assertEquals(200, reassigned.statusCode(), reassigned.body());
        assertEquals("DECISION", stageOf(claimNumber),
                "reassignment preserves the stage");
        assertEquals(1, count("SELECT count(*) FROM claim_cover WHERE claim_id = ? "
                + "AND is_proposal = TRUE", idOf(claimNumber)),
                "proposals travel");
        assertTrue(count("SELECT count(*) FROM verification WHERE claim_id = ?",
                idOf(claimNumber)) >= 4,
                "verification history travels (3 defaults + 1 follow-up)");
    }

    // --- auth matrix -------------------------------------------------------------------------

    @Test
    void stagedEndpointAuthMatrix() throws Exception {
        String claimNumber = fileCoverFnol(
                "[{\"coverCode\":\"OPD\",\"claimedAmount\":5000}]");

        // Anonymous -> 401 everywhere.
        assertEquals(401,
                get("/api/claims/" + claimNumber + "/staged", null).statusCode());
        assertEquals(401, postJson("/api/claims/" + claimNumber + "/review", null,
                "{\"action\":\"ADVANCE\",\"rationale\":\"x\"}").statusCode());

        // Claimant -> 403 on staged/review (their surface is need-info-response).
        assertEquals(403,
                get("/api/claims/" + claimNumber + "/staged", claimantBearer())
                        .statusCode());
        assertEquals(403, postJson("/api/claims/" + claimNumber + "/review",
                claimantBearer(), "{\"action\":\"ADVANCE\",\"rationale\":\"x\"}")
                .statusCode());

        // Supervisor -> 403 on review (their surface is reassign + escalated decide).
        assertEquals(403, postJson("/api/claims/" + claimNumber + "/review",
                supervisorBearer(), "{\"action\":\"ADVANCE\",\"rationale\":\"x\"}")
                .statusCode());

        // Non-assignee adjuster -> 404.
        assertEquals(404, get("/api/claims/" + claimNumber + "/staged",
                JwtTestConfig.tokenFor("10000000-0000-0000-0000-000000000002",
                        "adjuster_l1")).statusCode());

        // Adjuster on the claimant round-trip -> 403.
        assertEquals(403, postJson("/api/claims/" + claimNumber + "/need-info-response",
                holderBearer(claimNumber), "{\"message\":\"x\"}").statusCode());
    }

    // --- helpers ---------------------------------------------------------------------------

    private static final java.util.concurrent.atomic.AtomicInteger LOSS_DAY =
            new java.util.concurrent.atomic.AtomicInteger(1);

    private String fileCoverFnol(String coversJson) throws Exception {
        return fileCoverFnolFor("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test", coversJson);
    }

    private String fileCoverFnolFor(String policyNumber, String holderName,
            String holderEmail, String coversJson) throws Exception {
        // Unique loss date per filing: the duplicate guard keys on policy + loss
        // date + cover set within 24h, so identical fixtures in one test file
        // distinct claims (all safely in the past).
        String lossDate = "2026-08-" + String.format("%02d",
                LOSS_DAY.getAndIncrement() % 27 + 1);
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        fields.put("policyNumber", policyNumber);
        fields.put("holderName", holderName);
        fields.put("holderEmail", holderEmail);
        fields.put("lossDate", lossDate);
        fields.put("lossLocation", "London");
        fields.put("lossDescription", "Hospital stay plus follow-up visits.");
        fields.put("covers", coversJson);
        HttpResponse<String> response = post("/api/claims", claimantBearerFor(holderEmail),
                multipart(fields));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    /** Files, advances, verifies, completes and assesses: returns a claim at DECISION. */
    private String driveToDecision() throws Exception {
        String claimNumber = fileCoverFnol(
                "[{\"coverCode\":\"HOSPITALIZATION\",\"claimedAmount\":200000},"
                        + "{\"coverCode\":\"OPD\",\"claimedAmount\":40000}]");
        driveClaimToDecision(claimNumber, holderBearer(claimNumber));
        return claimNumber;
    }

    private String driveToVerification() throws Exception {
        String claimNumber = fileCoverFnol(
                "[{\"coverCode\":\"HOSPITALIZATION\",\"claimedAmount\":200000},"
                        + "{\"coverCode\":\"OPD\",\"claimedAmount\":40000}]");
        String bearer = holderBearer(claimNumber);
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"ADVANCE\",\"rationale\":\"Verifying.\"}").statusCode());
        HttpResponse<String> created = postJson(
                "/api/claims/" + claimNumber + "/verifications", bearer,
                "{\"type\":\"PHYSICAL\",\"notes\":\"Visiting the hospital.\"}");
        assertEquals(200, created.statusCode(), created.body());
        return claimNumber;
    }

    private void driveClaimToDecision(String claimNumber, String bearer) throws Exception {
        driveClaimToDecision(claimNumber, bearer, null);
    }

    /**
     * Assessment figures per cover; null = the default two-cover fixture
     * (HOSPITALIZATION 180000 + OPD 25000). Single-cover filings pass their own
     * figure keyed by the filed cover.
     */
    private void driveClaimToDecision(String claimNumber, String bearer,
            java.util.Map<String, String> assessedByCover) throws Exception {
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"ADVANCE\",\"rationale\":\"Verifying.\"}").statusCode());
        HttpResponse<String> created = postJson(
                "/api/claims/" + claimNumber + "/verifications", bearer,
                "{\"type\":\"DIGITAL\",\"notes\":\"Checking records.\"}");
        assertEquals(200, created.statusCode(), created.body());
        long verificationId = idOfVerification(created.body());
        HttpResponse<String> completed = putJson(
                "/api/claims/" + claimNumber + "/verifications/" + verificationId, bearer,
                "{\"status\":\"COMPLETE\",\"outcome\":\"PASSED\",\"notes\":\"Match.\"}");
        assertEquals(200, completed.statusCode(), completed.body());
        completeAllVerifications(claimNumber, bearer);
        String body;
        if (assessedByCover == null) {
            body = "{\"rationale\":\"Bills verified.\",\"covers\":["
                    + "{\"coverCode\":\"HOSPITALIZATION\",\"assessedAmount\":180000},"
                    + "{\"coverCode\":\"OPD\",\"assessedAmount\":25000}]}";
        } else {
            StringBuilder covers = new StringBuilder();
            for (java.util.Map.Entry<String, String> entry : assessedByCover.entrySet()) {
                if (covers.length() > 0) {
                    covers.append(",");
                }
                covers.append("{\"coverCode\":\"").append(entry.getKey())
                        .append("\",\"assessedAmount\":").append(entry.getValue())
                        .append("}");
            }
            body = "{\"rationale\":\"Bills verified.\",\"covers\":["
                    + covers + "]}";
        }
        body = withExpectedVersion(claimNumber, body);
        HttpResponse<String> assessed = putJson(
                "/api/claims/" + claimNumber + "/assessment", bearer, body);
        assertEquals(200, assessed.statusCode(), assessed.body());
    }

    /** V21 (V3 S5): splices expectedVersion into an assessment JSON body under test. */
    private String withExpectedVersion(String claimNumber, String json) {
        Long version = versionOf(claimNumber);
        return json.endsWith("}") ? json.substring(0, json.length() - 1)
                + ",\"expectedVersion\":" + version + "}" : json;
    }

    private void proposeAboveL1(String claimNumber, String bearer) throws Exception {
        HttpResponse<String> gated = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"Needs senior sign-off here.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":150000,\"remarks\":\"Large bill.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Waiting period.\","
                        + "\"denialReason\":\"EXCLUDED_PER_CLAUSE\"}]}");
        assertEquals(200, gated.statusCode(), gated.body());
        assertTrue(gated.body().contains("\"proposalsSaved\":true"), gated.body());
    }

    /**
     * Provisioning-gap helper (pair with {@link #restoreSeniorAdjusters}): removes
     * L2/L3 adjusters so auto-referral finds no covering rung with a candidate.
     * Restores by re-inserting (fresh ids, same seeded identities — mirroring
     * ClaimDecisionIntegrationTest's gap test). Adjuster skills for the restored
     * rows are re-seeded too (same product sets as V13).
     */
    private void deleteSeniorAdjusters() {
        jdbcTemplate.update("DELETE FROM adjuster_skill WHERE adjuster_id IN "
                + "(SELECT id FROM app_user WHERE level IN ('L2','L3'))");
        jdbcTemplate.update("DELETE FROM app_user WHERE level IN ('L2','L3')");
    }

    private void restoreSeniorAdjusters() {
        jdbcTemplate.update("INSERT INTO app_user (keycloak_sub, display_name, email,"
                + " level) VALUES "
                + "('10000000-0000-0000-0000-000000000003','Ines Kowalski',"
                + "'ines.kowalski@claims.test','L2'),"
                + "('10000000-0000-0000-0000-000000000006','Rahul Singh',"
                + "'rahul.singh@claims.test','L2'),"
                + "('10000000-0000-0000-0000-000000000007','Meera Nair',"
                + "'meera.nair@claims.test','L3')");
        jdbcTemplate.update("INSERT INTO adjuster_skill (adjuster_id, product_code) "
                + "SELECT a.id, s.product_code FROM app_user a JOIN (VALUES "
                + "('000000000003', 'AUTO'), ('000000000003', 'AUTO-COM'), "
                + "('000000000003', 'HLTH-CRIT'), ('000000000006', 'HLTH-PLUS'), "
                + "('000000000006', 'HLTH-CRIT'), ('000000000006', 'AUTO-COM'), "
                + "('000000000007', 'HLTH-BASIC'), ('000000000007', 'HLTH-PLUS'), "
                + "('000000000007', 'HLTH-CRIT'), ('000000000007', 'AUTO-STD'), "
                + "('000000000007', 'AUTO-COM'), ('000000000007', 'PROP-HOME'), "
                + "('000000000007', 'PROP-FIRE'), ('000000000007', 'HOME'), "
                + "('000000000007', 'AUTO')) "
                + "AS s(sub_frag, product_code) "
                + "ON a.keycloak_sub LIKE '%' || s.sub_frag "
                + "WHERE a.keycloak_sub IN "
                + "('10000000-0000-0000-0000-000000000003', "
                + "'10000000-0000-0000-0000-000000000006', "
                + "'10000000-0000-0000-0000-000000000007')");
    }

    private long idOfVerification(String body) {
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /**
     * Completes every non-COMPLETE verification row on the claim (the default
     * PHYSICAL/DOCUMENT/CLAUSE checklist plus any follow-ups). Assessment gates
     * on the whole checklist, so every driver that assesses must finish it.
     */
    private void completeAllVerifications(String claimNumber, String bearer)
            throws Exception {
        String staged = get("/api/claims/" + claimNumber + "/staged", bearer).body();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                        "\"id\":(\\d+),\"type\":([^,]+),\"status\":\"([A-Z_]+)\"")
                        .matcher(staged);
        while (matcher.find()) {
            if (!"COMPLETE".equals(matcher.group(3))) {
                HttpResponse<String> completed = putJson("/api/claims/" + claimNumber
                        + "/verifications/" + matcher.group(1), bearer,
                        "{\"status\":\"COMPLETE\",\"outcome\":\"PASSED\","
                                + "\"notes\":\"Checked.\"}");
                assertEquals(200, completed.statusCode(), completed.body());
            }
        }
    }

    /** Pre-decision referral at REVIEW: moves to a higher rung, stage untouched. */
    @Test
    void referFromReviewMovesToHigherRungWithStageUntouched() throws Exception {
        String claimNumber = fileCoverFnol(
                "[{\"coverCode\":\"OPD\",\"claimedAmount\":5000}]");
        String actorBearer = holderBearer(claimNumber);

        HttpResponse<String> referred = postJson("/api/claims/" + claimNumber + "/refer",
                actorBearer, "{\"auto\":true,\"reason\":\"Needs a senior eye.\"}");
        assertEquals(200, referred.statusCode(), referred.body());
        assertTrue(referred.body().contains("\"escalatedTo\":\"L2\""), referred.body());
        assertEquals("REVIEW", stageOf(claimNumber),
                "pre-decision referral preserves the stage");
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        // The original actor lost access; a higher rung holds it now.
        assertEquals(404,
                get("/api/claims/" + claimNumber + "/staged", actorBearer).statusCode());
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = "
                + "'CLAIM_REFERRED' AND entity_id = ?", idOf(claimNumber)));
    }

    /** Pre-decision referral at VERIFICATION: verifications travel with the claim. */
    @Test
    void referFromVerificationPreservesVerificationHistory() throws Exception {
        String claimNumber = driveToVerification();
        String actorBearer = holderBearer(claimNumber);
        long rowsBefore = count("SELECT count(*) FROM verification WHERE claim_id = ?",
                idOf(claimNumber));

        HttpResponse<String> referred = postJson("/api/claims/" + claimNumber + "/refer",
                actorBearer, "{\"auto\":true,\"reason\":\"Complex bills.\"}");
        assertEquals(200, referred.statusCode(), referred.body());
        assertEquals("VERIFICATION", stageOf(claimNumber),
                "referral preserves the stage");
        assertEquals(rowsBefore, count(
                "SELECT count(*) FROM verification WHERE claim_id = ?",
                idOf(claimNumber)),
                "verification history travels");
    }

    /** Pre-decision referral without a target is a 400 (nothing to resolve). */
    @Test
    void referFromReviewWithoutTargetIsRejected() throws Exception {
        String claimNumber = fileCoverFnol(
                "[{\"coverCode\":\"OPD\",\"claimedAmount\":5000}]");
        assertEquals(400, postJson("/api/claims/" + claimNumber + "/refer",
                holderBearer(claimNumber), "{\"reason\":\"Nowhere.\"}").statusCode());
    }

    /** V18 send-back: DECISION→VERIFICATION clears proposals, audit survives. */
    @Test
    void sendBackFromDecisionClearsProposalsAndKeepsHistory() throws Exception {
        String claimNumber = driveToDecision();
        String bearer = holderBearer(claimNumber);
        proposeAboveL1(claimNumber, bearer);
        assertEquals(1, count("SELECT count(*) FROM claim_cover WHERE claim_id = ? "
                + "AND is_proposal = TRUE", idOf(claimNumber)));

        HttpResponse<String> sentBack = postJson(
                "/api/claims/" + claimNumber + "/send-back", bearer,
                "{\"rationale\":\"New doubts on the bills — re-checking.\"}");
        assertEquals(200, sentBack.statusCode(), sentBack.body());
        assertTrue(sentBack.body().contains("\"stage\":\"VERIFICATION\""),
                sentBack.body());
        assertEquals("VERIFICATION", stageOf(claimNumber));
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals(0, count("SELECT count(*) FROM claim_cover WHERE claim_id = ? "
                + "AND is_proposal = TRUE", idOf(claimNumber)),
                "proposals clear on the step back");
        assertTrue(count("SELECT count(*) FROM verification WHERE claim_id = ?",
                idOf(claimNumber)) >= 4,
                "verification history travels");
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT assessed_amount FROM claim_cover WHERE claim_id = ? LIMIT 1",
                java.math.BigDecimal.class, idOf(claimNumber)),
                "assessed figures stay as re-usable input");
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = "
                + "'STAGE_SENT_BACK' AND entity_id = ?", idOf(claimNumber)));
        String feed = get("/api/claims/" + claimNumber + "/timeline", bearer).body();
        assertTrue(feed.contains("STAGE_SENT_BACK"), feed);

        // The holder can re-assess and move forward again.
        HttpResponse<String> reassessed = putJson(
                "/api/claims/" + claimNumber + "/assessment", bearer,
                "{\"rationale\":\"Re-checked the bills.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"assessedAmount\":180000},"
                        + "{\"coverCode\":\"OPD\",\"assessedAmount\":25000}]}");
        assertEquals(200, reassessed.statusCode(), reassessed.body());
        assertEquals("DECISION", stageOf(claimNumber));

        // Guards: send-back at REVIEW is a 400; without a reason is a 400.
        String fresh = fileCoverFnol(
                "[{\"coverCode\":\"OPD\",\"claimedAmount\":5000}]");
        assertEquals(400, postJson("/api/claims/" + fresh + "/send-back",
                holderBearer(fresh), "{\"rationale\":\"Nowhere to go.\"}").statusCode());
        assertEquals(400, postJson("/api/claims/" + claimNumber + "/send-back",
                holderBearer(claimNumber), "{}").statusCode());
    }

    /** V16 claimant document upload: NEED_INFO only, own claim only. */
    @Test
    void claimantDocumentUploadNeedsNeedInfoAndOwnClaim() throws Exception {
        String claimNumber = driveToVerification();
        String bearer = holderBearer(claimNumber);
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"NEED_INFO\",\"requestedItems\":\"Upload the bill.\"}")
                .statusCode());

        // Foreign claimant is a 404, never a 403.
        assertEquals(404, postMultipart(
                "/api/claims/" + claimNumber + "/documents",
                JwtTestConfig.tokenFor("sub-claimant-other", "claimant"),
                "bill.pdf", "Bill", "image/png", new byte[] {(byte) 0x89, 0x50})
                .statusCode());

        // Upload while NEED_INFO: labelled row lands, visible to the assignee.
        java.net.http.HttpResponse<String> uploaded = postMultipart(
                "/api/claims/" + claimNumber + "/documents", claimantBearer(),
                "bill.pdf", "Hospital bill", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertTrue(uploaded.body().contains("Hospital bill"), uploaded.body());

        // After the claimant responds the claim is back UNDER_REVIEW: uploads stop.
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/need-info-response",
                claimantBearer(), "{\"message\":\"Uploaded.\"}").statusCode());
        assertEquals(400, postMultipart(
                "/api/claims/" + claimNumber + "/documents", claimantBearer(),
                "late.pdf", "Late", "image/png", new byte[] {(byte) 0x89, 0x50})
                .statusCode());
    }

    /**
     * V17 timeline: one sequential feed of notes, documents (claim-level +
     * per-check with uploaders) and milestones, visible to the holder (404 for
     * anyone else). Entries persist across referral — the new holder sees the
     * same history.
     */
    @Test
    void timelineUnifiesNotesDocumentsAndMilestonesAcrossReferral() throws Exception {
        String claimNumber = driveToVerification();
        String bearer = holderBearer(claimNumber);

        // A note + a claim-level document by the holder.
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/notes", bearer,
                "{\"body\":\"Bills look consistent.\"}").statusCode());
        java.net.http.HttpResponse<String> uploaded = postMultipart(
                "/api/claims/" + claimNumber + "/attachments", bearer,
                "bill.pdf", "Hospital bill", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertTrue(uploaded.body().contains("Hospital bill"), uploaded.body());

        // A per-check document linked to the first open verification row.
        String staged = get("/api/claims/" + claimNumber + "/staged", bearer).body();
        long verificationId = Long.parseLong(staged.replaceAll(
                ".*\"verifications\":\\[\\{\"id\":(\\d+).*", "$1"));
        java.net.http.HttpResponse<String> linked = postMultipartWithVerification(
                "/api/claims/" + claimNumber + "/attachments", bearer,
                "site.jpg", "Damage photos", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}, verificationId);
        assertEquals(200, linked.statusCode(), linked.body());

        // Unknown verification links are a 404, never a leak.
        assertEquals(404, postMultipartWithVerification(
                "/api/claims/" + claimNumber + "/attachments", bearer,
                "x.pdf", "X", "image/png",
                new byte[] {(byte) 0x89, 0x50}, 999999L).statusCode());

        // The timeline carries every row with actor + document identity.
        HttpResponse<String> feed = get("/api/claims/" + claimNumber + "/timeline",
                bearer);
        assertEquals(200, feed.statusCode(), feed.body());
        assertTrue(feed.body().contains("\"kind\":\"FILED\""), feed.body());
        assertTrue(feed.body().contains("\"kind\":\"NOTE\""), feed.body());
        assertTrue(feed.body().contains("Bills look consistent."), feed.body());
        assertTrue(feed.body().contains("\"kind\":\"DOCUMENT\""), feed.body());
        assertTrue(feed.body().contains("Hospital bill"), feed.body());
        assertTrue(feed.body().contains("Damage photos"), feed.body());
        assertTrue(feed.body().contains("REVIEW_ADVANCED"), feed.body());
        assertTrue(feed.body().contains("VERIFICATION_OPENED"), feed.body());

        // A stranger's claim number is a 404.
        assertEquals(404, get("/api/claims/" + claimNumber + "/timeline",
                JwtTestConfig.tokenFor("10000000-0000-0000-0000-000000000002",
                        "adjuster_l1"))
                .statusCode());

        // Referral preserves the feed: the new holder sees the same rows.
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/refer",
                bearer, "{\"auto\":true,\"reason\":\"Needs a senior eye.\"}")
                .statusCode());
        String newSub = assigneeSubOf(claimNumber);
        String newLevel = jdbcTemplate.queryForObject(
                "SELECT a.level FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        HttpResponse<String> afterRefer = get(
                "/api/claims/" + claimNumber + "/timeline",
                JwtTestConfig.tokenFor(newSub, "adjuster_" + newLevel.toLowerCase()));
        assertEquals(200, afterRefer.statusCode(), afterRefer.body());
        assertTrue(afterRefer.body().contains("Bills look consistent."),
                afterRefer.body());
        assertTrue(afterRefer.body().contains("Hospital bill"), afterRefer.body());
        assertTrue(afterRefer.body().contains("Damage photos"), afterRefer.body());
    }

    /** Multipart upload with an explicit verificationId part (V17 per-check link). */
    private HttpResponse<String> postMultipartWithVerification(String path, String bearer,
            String filename, String label, String contentType, byte[] bytes,
            long verificationId) throws Exception {
        String partBoundary = "----StagedVerDocBoundary9";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        String fileHead = "--" + partBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        out.write(fileHead.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(bytes);
        out.write(("\r\n--" + partBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"label\"\r\n\r\n"
                + label + "\r\n--" + partBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"verificationId\"\r\n\r\n"
                + verificationId + "\r\n--" + partBoundary + "--\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "multipart/form-data; boundary=" + partBoundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT id FROM claim WHERE claim_number = ?",
                Long.class, claimNumber);
    }

    /** V21 (V3 S5): the claim version a writer must echo back as expectedVersion. */
    private Long versionOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT version FROM claim WHERE claim_number = ?",
                Long.class, claimNumber);
    }

    private String statusOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT status FROM claim WHERE claim_number = ?",
                String.class, claimNumber);
    }

    private String stageOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT stage FROM claim WHERE claim_number = ?",
                String.class, claimNumber);
    }

    private String decisionOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT decision FROM claim WHERE claim_number = ?",
                String.class, claimNumber);
    }

    private String assigneeSubOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT a.keycloak_sub FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
    }

    private String bearerSub(String bearer) {
        if (bearer == null) {
            return null;
        }
        String[] parts = bearer.split("\\.");
        String payload = new String(
                java.util.Base64.getUrlDecoder().decode(parts[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        return payload.replaceAll(".*\"sub\":\"([^\"]+)\".*", "$1");
    }

    private String holderBearer(String claimNumber) {
        String sub = assigneeSubOf(claimNumber);
        String level = jdbcTemplate.queryForObject(
                "SELECT a.level FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        return JwtTestConfig.tokenFor(sub, "adjuster_" + level.toLowerCase());
    }

    private String claimantBearer() {
        return JwtTestConfig.tokenFor(CLAIMANT, "claimant");
    }

    private String claimantBearerFor(String holderEmail) {
        if ("fatima.khan@example.test".equals(holderEmail)) {
            return JwtTestConfig.tokenFor("sub-claimant-crit", "claimant");
        }
        return JwtTestConfig.tokenFor(CLAIMANT, "claimant");
    }

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor(SUB_SUPERVISOR, "supervisor");
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private HttpResponse<String> postJson(String path, String bearer, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(String path, String bearer, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String bearer, byte[] body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Multipart file upload with a label part (V16 document endpoints). */
    private HttpResponse<String> postMultipart(String path, String bearer,
            String filename, String label, String contentType, byte[] bytes)
            throws Exception {
        String partBoundary = "----StagedDocBoundary9";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        String fileHead = "--" + partBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        out.write(fileHead.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(bytes);
        out.write(("\r\n--" + partBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"label\"\r\n\r\n"
                + label + "\r\n--" + partBoundary + "--\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "multipart/form-data; boundary=" + partBoundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipart(java.util.Map<String, String> fields) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (java.util.Map.Entry<String, String> field : fields.entrySet()) {
                out.write(("--" + BOUNDARY + "\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + field.getKey()
                        + "\"\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.write((field.getValue() + "\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            out.write(("--" + BOUNDARY + "--\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
