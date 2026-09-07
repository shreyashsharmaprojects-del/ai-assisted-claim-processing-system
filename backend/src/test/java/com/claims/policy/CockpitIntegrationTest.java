package com.claims.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * V2-1 acceptance: catalog + dummy-data seed integrity and the claimant cockpit read
 * path. Real HTTP against real Postgres (Flyway V12/V13 lay down the catalog, covers,
 * skills, L3 staff, and enriched policies).
 *
 * <p>Pins: 10 products in 3 families; 12 policies (incl. RETIRED + EXPIRED); 25 covers
 * with Ada's 5-cover HLTH-PLUS set; 6 adjusters across L1/L2/L3; skill overlap on
 * HLTH-BASIC and an empty HLTH-ORPHAN; authority rows carry L3 limits, basis, and SLA
 * fields with V1 HOME/AUTO semantics preserved.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CockpitIntegrationTest extends ClaimTableResettingTest {

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    // --- seed integrity ---------------------------------------------------------

    @Test
    void productCatalogHasTenCodesInThreeFamilies() {
        assertEquals(10, count("SELECT count(*) FROM product"));
        assertEquals(3, count("SELECT count(DISTINCT family) FROM product"));
        assertEquals(4, count("SELECT count(*) FROM product WHERE family = 'HEALTH'"),
                "health family carries BASIC/PLUS/CRIT + the orphan row");
    }

    @Test
    void twelvePoliciesWithRetiredAndExpiredRows() {
        assertEquals(12, count("SELECT count(*) FROM policy"));
        assertEquals("RETIRED", statusOf("POL-30007"));
        assertEquals("EXPIRED", statusOf("POL-30008"));
        assertEquals("HLTH-PLUS",
                jdbcTemplate.queryForObject(
                        "SELECT product_code FROM policy WHERE policy_number = 'POL-10001'",
                        String.class),
                "Ada's V1 policy is enriched to the HLTH-PLUS 5-cover set");
        assertEquals("AUTO",
                jdbcTemplate.queryForObject(
                        "SELECT product_code FROM policy WHERE policy_number = 'POL-20002'",
                        String.class),
                "Grace's V1 AUTO policy keeps its code for V1 regression");
    }

    @Test
    void adaHasFiveCoversAndGraceHasOneDegenerateCover() {
        assertEquals(5, coverCount("POL-10001"));
        assertEquals(1, coverCount("POL-20002"));
        assertEquals(25, count("SELECT count(*) FROM policy_cover"));
        assertTrue(coversOf("POL-10001").containsAll(
                List.of("HOSPITALIZATION", "ROOM_RENT", "DAYCARE", "OPD", "MATERNITY")),
                "S1 partial-approval fuel: " + coversOf("POL-10001"));
    }

    @Test
    void sixAdjustersAcrossThreeRungsWithSkillOverlapAndOrphanGap() {
        assertEquals(6, count("SELECT count(*) FROM app_user"));
        assertEquals(3, count("SELECT count(*) FROM app_user WHERE level = 'L1'"));
        assertEquals(2, count("SELECT count(*) FROM app_user WHERE level = 'L2'"));
        assertEquals(1, count("SELECT count(*) FROM app_user WHERE level = 'L3'"));
        assertEquals(2, count("SELECT count(DISTINCT adjuster_id) FROM adjuster_skill "
                + "WHERE product_code = 'HLTH-BASIC' AND adjuster_id IN "
                + "(SELECT id FROM app_user WHERE level = 'L1')"),
                "HLTH-BASIC overlaps two L1 adjusters (S6 tie-break fuel)");
        assertEquals(0, count("SELECT count(*) FROM adjuster_skill "
                + "WHERE product_code = 'HLTH-ORPHAN'"),
                "HLTH-ORPHAN is deliberately unmapped (S5/E5)");
    }

    @Test
    void authorityRowsCarryL3LimitsBasisAndSlaWithV1SemanticsPreserved() {
        assertEquals(10, count("SELECT count(*) FROM authority_config"));
        for (String code : new String[] {"HOME", "AUTO"}) {
            assertTrue(limitOf(code, "l3_limit_amount").signum() > 0,
                    code + " gained an L3 limit");
            assertEquals("APPROVED_TOTAL",
                    jdbcTemplate.queryForObject(
                            "SELECT authority_basis FROM authority_config WHERE product_code = ?",
                            String.class, code));
            assertEquals(2, intOf(code, "sla_warning_days"));
            assertEquals(3, intOf(code, "sla_breach1_days"));
            assertEquals("ESCALATE_NEXT_LEVEL",
                    jdbcTemplate.queryForObject(
                            "SELECT sla_breach1_action FROM authority_config WHERE product_code = ?",
                            String.class, code));
            assertEquals(5, intOf(code, "sla_breach2_days"));
            assertEquals("ESCALATE_SUPERVISOR",
                    jdbcTemplate.queryForObject(
                            "SELECT sla_breach2_action FROM authority_config WHERE product_code = ?",
                            String.class, code));
        }
    }

    // --- cockpit ------------------------------------------------------------------

    @Test
    void claimantSeesOwnPoliciesWithCoversAndRemainingBenefit() throws Exception {
        HttpResponse<String> response = get("/api/policies/mine", adaBearer());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("POL-10001"), response.body());
        assertTrue(response.body().contains("HLTH-PLUS"), response.body());
        assertTrue(response.body().contains("remainingBenefit"), response.body());
        assertTrue(!response.body().contains("ada.lovelace@example.test"),
                "holder email never reaches the cockpit view: " + response.body());
        assertTrue(!response.body().contains("claimantSub"),
                "no internal fields leak: " + response.body());
    }

    @Test
    void claimantCannotSeeAnotherHoldersPolicies() throws Exception {
        HttpResponse<String> response = get("/api/policies/mine",
                JwtTestConfig.tokenFor("sub-stranger", "claimant"));
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().equals("[]"), "a stranger owns no policies: " + response.body());
    }

    @Test
    void policyDetailShowsCoversRatingAndClausesButNotEmail() throws Exception {
        HttpResponse<String> response = get("/api/policies/POL-10001", adaBearer());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("HOSPITALIZATION"), response.body());
        assertTrue(response.body().contains("OPD"), response.body());
        assertTrue(response.body().contains("room_rent_cap_per_day"), response.body());
        assertTrue(response.body().contains("Cosmetic surgery"), response.body());
        assertTrue(response.body().contains("remainingSubLimit"), response.body());
        assertTrue(!response.body().contains("ada.lovelace@example.test"),
                "holder email never reaches the detail view: " + response.body());
    }

    @Test
    void policyDetailForAnotherHoldersPolicyIs404() throws Exception {
        HttpResponse<String> response = get("/api/policies/POL-30001", adaBearer());
        assertEquals(404, response.statusCode(),
                "another holder's policy reads as missing (404, not 403)");
    }

    @Test
    void cockpitMineIsClaimantOnly() throws Exception {
        assertEquals(401, get("/api/policies/mine", null).statusCode());
        assertEquals(403, get("/api/policies/mine",
                JwtTestConfig.tokenFor("sub-l1", "adjuster_l1")).statusCode());
        assertEquals(403, get("/api/policies/mine",
                JwtTestConfig.tokenFor("sub-sup", "supervisor")).statusCode());
    }

    @Test
    void legacyPolicyListStillServesSeedsForV1Clients() throws Exception {
        HttpResponse<String> response = get("/api/policies",
                JwtTestConfig.tokenFor("sub-policy-1", "claimant"));
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("POL-10001"), response.body());
        assertTrue(response.body().contains("Ada Lovelace"), response.body());
    }

    // --- helpers ------------------------------------------------------------------

    private static String adaBearer() {
        return JwtTestConfig.tokenFor("sub-ada", "claimant",
                "email:ada.lovelace@example.test");
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String statusOf(String policyNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM policy WHERE policy_number = ?", String.class,
                policyNumber);
    }

    private long coverCount(String policyNumber) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM policy_cover WHERE policy_id = "
                        + "(SELECT id FROM policy WHERE policy_number = ?)",
                Long.class, policyNumber);
        return value == null ? 0 : value;
    }

    private List<String> coversOf(String policyNumber) {
        return jdbcTemplate.queryForList(
                "SELECT cover_code FROM policy_cover WHERE policy_id = "
                        + "(SELECT id FROM policy WHERE policy_number = ?) "
                        + "ORDER BY sort_order",
                String.class, policyNumber);
    }

    private java.math.BigDecimal limitOf(String productCode, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM authority_config WHERE product_code = ?",
                java.math.BigDecimal.class, productCode);
    }

    private int intOf(String productCode, String column) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM authority_config WHERE product_code = ?",
                Integer.class, productCode);
        return value == null ? -1 : value;
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
