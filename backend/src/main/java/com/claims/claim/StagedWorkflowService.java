package com.claims.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.api.ClaimNotFoundException;
import com.claims.api.InvalidRequestException;
import com.claims.assignment.AdjusterSkillRepository;
import com.claims.assignment.ClaimAssigner;
import com.claims.assignment.LoadBalancer;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.metrics.ClaimsMetrics;
import com.claims.outbox.EmailOutboxWriter;
import com.claims.policy.Policy;
import com.claims.policy.PolicyCover;
import com.claims.policy.PolicyCoverRepository;
import com.claims.policy.PolicyRepository;
import com.claims.routing.AuthorityConfig;
import com.claims.routing.AuthorityConfigRepository;
import com.claims.staff.AppUser;
import com.claims.staff.AppUserRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * V2-4/V2-5/V2-6 backend: the staged adjuster workflow on top of the V1 claim.
 * Stages (REVIEW | VERIFICATION | DECISION) are orthogonal to the V1 routing status;
 * NEED_INFO parks the claim with its prior stage for the claimant round-trip (from any
 * stage); cover assessment binds the financial chain once the whole verification
 * checklist is COMPLETE; cover decisions close within authority or save proposals that
 * travel on explicit referral — nothing ever moves by itself.
 *
 * <p>Legacy no-cover claims keep the V1 decision behaviour through
 * {@link ClaimDecisionService} (see {@link #decide}); everything here 404s through
 * {@link ClaimAccess} like every other per-claim surface.
 */
@Service
public class StagedWorkflowService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");

    /** Verification types: the default checklist plus ad-hoc follow-ups. */
    private static final List<String> VERIFICATION_TYPES =
            List.of("DIGITAL", "PHYSICAL", "DOCUMENT", "CLAUSE");

    private static void requireVerificationType(String type) {
        if (type == null || !VERIFICATION_TYPES.contains(type)) {
            throw new InvalidRequestException(
                    "Verification type must be DIGITAL, PHYSICAL, DOCUMENT or CLAUSE.");
        }
    }

    private final ClaimRepository claims;
    private final PolicyRepository policies;
    private final PolicyCoverRepository policyCovers;
    private final ClaimCoverRepository claimCovers;
    private final VerificationRepository verifications;
    private final AuthorityConfigRepository authorityConfigs;
    private final AppUserRepository appUsers;
    private final AdjusterSkillRepository skills;
    private final ClaimAccess access;
    private final ClaimAssigner assigner;
    private final ClaimDecisionService legacyDecisions;
    private final PaymentRepository payments;
    private final AuditLogWriter auditLog;
    private final ClaimsMetrics metrics;
    private final EmailOutboxWriter outboxWriter;
    private final JdbcTemplate jdbcTemplate;
    private final EscalationDecisionService escalationDecisions;
    private final RequiredDocumentService requiredDocuments;

    public StagedWorkflowService(ClaimRepository claims, PolicyRepository policies,
            PolicyCoverRepository policyCovers, ClaimCoverRepository claimCovers,
            VerificationRepository verifications,
            AuthorityConfigRepository authorityConfigs, AppUserRepository appUsers,
            AdjusterSkillRepository skills, ClaimAccess access, ClaimAssigner assigner,
            ClaimDecisionService legacyDecisions, PaymentRepository payments,
            AuditLogWriter auditLog, ClaimsMetrics metrics, EmailOutboxWriter outboxWriter,
            JdbcTemplate jdbcTemplate, EscalationDecisionService escalationDecisions,
            RequiredDocumentService requiredDocuments) {
        this.claims = claims;
        this.policies = policies;
        this.policyCovers = policyCovers;
        this.claimCovers = claimCovers;
        this.verifications = verifications;
        this.authorityConfigs = authorityConfigs;
        this.appUsers = appUsers;
        this.skills = skills;
        this.access = access;
        this.assigner = assigner;
        this.legacyDecisions = legacyDecisions;
        this.payments = payments;
        this.auditLog = auditLog;
        this.metrics = metrics;
        this.outboxWriter = outboxWriter;
        this.jdbcTemplate = jdbcTemplate;
        this.escalationDecisions = escalationDecisions;
        this.requiredDocuments = requiredDocuments;
    }

    // --- staged full view ------------------------------------------------------

    @Transactional(readOnly = true)
    public StagedClaimView fullView(String claimNumber, String actorSub, boolean supervisor) {
        Claim claim = requireAssignee(claimNumber, actorSub, supervisor);
        return viewOf(claim, actorSub);
    }

    // --- review ----------------------------------------------------------------

    @Transactional
    public StagedClaimView review(String claimNumber, String actorSub, boolean supervisor,
            ReviewInput input) {
        Claim claim = requireAssigneeForUpdate(claimNumber, actorSub, supervisor);
        String action = input == null ? null : input.action();
        if (!"ADVANCE".equals(action) && !"REJECT".equals(action)
                && !"NEED_INFO".equals(action)) {
            throw new InvalidRequestException("Review action must be ADVANCE, REJECT or NEED_INFO.");
        }
        String rationale = textOf(input == null ? null : input.rationale());
        String notes = textOf(input == null ? null : input.notes());
        String requested = textOf(input == null ? null : input.requestedItems());
        Instant now = Instant.now();

        if ("ADVANCE".equals(action)) {
            if (!"UNDER_REVIEW".equals(claim.getStatus()) || !"REVIEW".equals(stageOf(claim))) {
                throw new InvalidRequestException(
                        "Only a claim under review can be advanced to verification.");
            }
            requireRationale(rationale, "A rationale is required.");
            claim.setStage("VERIFICATION");
            claims.save(claim);
            // The default verification checklist: physical inspection, document
            // check, clause check. Follow-up DIGITAL/PHYSICAL rows stay allowed;
            // completing a default row never rewrites history (see updateVerification).
            List<Long> opened = new ArrayList<>();
            for (String defaultType : List.of("PHYSICAL", "DOCUMENT", "CLAUSE")) {
                Verification row = verifications.save(
                        new Verification(claim.getId(), defaultType, "PENDING", actorSub,
                                now));
                opened.add(row.getId());
            }
            audit("REVIEW_ADVANCED", actorSub, claim,
                    Map.of("status", "UNDER_REVIEW", "stage", "REVIEW"),
                    after(claim, "stage", "VERIFICATION", "verificationIds", opened),
                    rationale);
            return viewOf(claim, actorSub);
        }
        if ("REJECT".equals(action)) {
            if (!"UNDER_REVIEW".equals(claim.getStatus()) || !"REVIEW".equals(stageOf(claim))) {
                throw new InvalidRequestException(
                        "Only a claim under review can be rejected at review.");
            }
            requireRationale(rationale, "A rationale is required to reject a claim.");
            String before = claim.getStatus() + "/" + stageOf(claim);
            claim.deny(rationale, now);
            claims.save(claim);
            metrics.decision("DENIED");
            audit("DECISION", actorSub, claim, Map.of("statusStage", before),
                    after(claim, "decision", "DENIED", "decisionRemarks", rationale), rationale);
            Policy policy = policyOf(claim);
            ClaimDecisionView legacyView = new ClaimDecisionView(claim.getClaimNumber(),
                    claim.getDecision(), claim.getIndemnityAmount(),
                    claim.getDecisionRemarks(), null);
            outboxWriter.enqueueDecision(claim.getId(), policy.getHolderEmail(),
                    policy.getHolderName(), legacyView);
            return viewOf(claim, actorSub);
        }
        // NEED_INFO: any stage (REVIEW | VERIFICATION | DECISION), never twice.
        if (!"UNDER_REVIEW".equals(claim.getStatus())) {
            throw new InvalidRequestException(
                    "Information can only be requested while a claim is under review.");
        }
        String reason = requested != null ? requested : rationale;
        if (notes != null && reason == null) {
            reason = notes;
        }
        requireRationale(reason, "Requested items are required to send a claim back.");
        String priorStage = stageOf(claim);
        claim.setNeedInfoPriorStage(priorStage);
        claim.setNeedInfoReason(reason);
        claim.setStage(priorStage);
        // Leaves the assignee bucket: the response reassigns through the assigner.
        claim.setStatus("NEED_INFO");
        claim.setAssignedAdjusterId(null, null);
        claims.save(claim);
        audit("NEED_INFO_SENT", actorSub, claim,
                Map.of("status", "UNDER_REVIEW", "stage", priorStage),
                after(claim, "needInfoPriorStage", priorStage, "needInfoReason", reason),
                reason);
        return viewOf(claim, actorSub);
    }

    // --- verifications ----------------------------------------------------------

    /**
     * V18: send a claim one step back — DECISION back to VERIFICATION when new
     * doubts arise (the plan's normative re-open path), or VERIFICATION back to
     * REVIEW to re-triage. Saved decision proposals do not survive the step back:
     * they described a decision at a stage the claim no longer occupies, so they
     * are cleared to ordinary PENDING rows (assessed figures stay — they are
     * re-usable input, not a verdict). Every send-back writes a STAGE_SENT_BACK
     * audit row with the rationale. Terminal states (CLOSED, NEED_INFO,
     * ESCALATED_SUPERVISOR) cannot move.
     */
    @Transactional
    public StagedClaimView sendBack(String claimNumber, String actorSub,
            boolean supervisor, SendBackInput input) {
        Claim claim = requireAssigneeForUpdate(claimNumber, actorSub, supervisor);
        requireOpenUnderReview(claim);
        String current = stageOf(claim);
        if ("REVIEW".equals(current)) {
            throw new InvalidRequestException(
                    "This claim is already at review — there is no earlier stage.");
        }
        String rationale = textOf(input == null ? null : input.rationale());
        requireRationale(rationale, "A reason is required to send a claim back.");
        String target = "DECISION".equals(current) ? "VERIFICATION" : "REVIEW";
        List<ClaimCover> rows = claimCovers.findByClaimIdOrderByIdAsc(claim.getId());
        long cleared = 0;
        for (ClaimCover row : rows) {
            if (row.isProposal()) {
                row.applyDecision("PENDING", null, row.getDeductibleAmount(),
                        row.getAdjustmentAmount(), null, null, null, null, false);
                claimCovers.save(row);
                cleared++;
            }
        }
        claim.setStage(target);
        claims.save(claim);
        Map<String, Object> after = after(claim, "stage", target);
        if (cleared > 0) {
            after.put("proposalsCleared", cleared);
        }
        audit("STAGE_SENT_BACK", actorSub, claim,
                Map.of("status", "UNDER_REVIEW", "stage", current), after, rationale);
        return viewOf(claim, actorSub);
    }

    @Transactional
    public StagedClaimView.VerificationView createVerification(String claimNumber,
            String actorSub, boolean supervisor, VerificationInput input) {
        Claim claim = requireAssigneeForUpdate(claimNumber, actorSub, supervisor);
        requireOpenUnderReview(claim);
        String type = input == null ? null : textOf(input.type());
        requireVerificationType(type);
        Instant now = Instant.now();
        Verification row = verifications.save(
                new Verification(claim.getId(), type, "IN_PROGRESS", actorSub, now));
        if (input != null && textOf(input.notes()) != null) {
            row.setNotes(textOf(input.notes()));
            verifications.save(row);
        }
        audit("VERIFICATION_CREATED", actorSub, claim, Map.of(),
                after(claim, "verificationId", row.getId(), "type", type), row.getNotes());
        return verificationView(row);
    }

    @Transactional
    public StagedClaimView.VerificationView updateVerification(String claimNumber, Long id,
            String actorSub, boolean supervisor, VerificationUpdate input) {
        Claim claim = requireAssigneeForUpdate(claimNumber, actorSub, supervisor);
        requireOpenUnderReview(claim);
        Verification row = verifications.findByIdAndClaimId(id, claim.getId())
                .orElseThrow(ClaimNotFoundException::new);
        if (input == null) {
            throw new InvalidRequestException("Verification update needs at least one field.");
        }
        String status = textOf(input.status());
        String type = textOf(input.type());
        String outcome = textOf(input.outcome());
        String notes = textOf(input.notes());
        String evidence = textOf(input.evidenceRefs());
        if (status == null && type == null && outcome == null && notes == null
                && evidence == null) {
            throw new InvalidRequestException("Verification update needs at least one field.");
        }
        if ("COMPLETE".equals(status)
                && !"COMPLETE".equals(row.getStatus())) {
            if (outcome == null || (!"PASSED".equals(outcome) && !"FAILED".equals(outcome)
                    && !"WAIVED".equals(outcome) && !"INCONCLUSIVE".equals(outcome))) {
                throw new InvalidRequestException(
                        "Completing a verification requires an outcome (PASSED, FAILED, WAIVED or INCONCLUSIVE).");
            }
            String completionNotes = notes != null ? notes : textOf(row.getNotes());
            if (completionNotes == null) {
                throw new InvalidRequestException(
                        "Completing a verification requires notes.");
            }
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("verificationId", row.getId());
        before.put("status", row.getStatus());
        if (type != null) {
            requireVerificationType(type);
            row.setType(type);
        }
        if (outcome != null) {
            if (!"PASSED".equals(outcome) && !"FAILED".equals(outcome)
                    && !"WAIVED".equals(outcome) && !"INCONCLUSIVE".equals(outcome)) {
                throw new InvalidRequestException(
                        "Verification outcome must be PASSED, FAILED, WAIVED or INCONCLUSIVE.");
            }
            row.setOutcome(outcome);
        }
        if (notes != null) {
            row.setNotes(notes);
        }
        if (evidence != null) {
            row.setEvidenceRefs(evidence);
        }
        if (status != null) {
            if (!"PENDING".equals(status) && !"IN_PROGRESS".equals(status)
                    && !"COMPLETE".equals(status) && !"CANCELLED".equals(status)) {
                throw new InvalidRequestException(
                        "Verification status must be PENDING, IN_PROGRESS, COMPLETE or CANCELLED.");
            }
            if ("CANCELLED".equals(status) && textOf(row.getNotes()) == null) {
                throw new InvalidRequestException(
                        "Cancelling a verification requires notes.");
            }
            row.setStatus(status);
            if ("COMPLETE".equals(status)) {
                row.setCompletedAt(Instant.now());
            }
        }
        row.setPerformedBy(actorSub);
        verifications.save(row);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("claimNumber", claim.getClaimNumber());
        after.put("verificationId", row.getId());
        after.put("status", row.getStatus());
        audit("VERIFICATION_UPDATED", actorSub, claim, before, after, row.getNotes());
        return verificationView(row);
    }

    // --- assessment --------------------------------------------------------------

    @Transactional
    public StagedClaimView assess(String claimNumber, String actorSub, boolean supervisor,
            AssessmentInput input) {
        Claim claim = requireAssigneeForUpdate(claimNumber, actorSub, supervisor);
        requireOpenUnderReview(claim);
        if (!"VERIFICATION".equals(stageOf(claim))) {
            throw new InvalidRequestException(
                    "Assessment is only available at the verification stage.");
        }
        List<Verification> history = verifications.findByClaimIdOrderByIdAsc(claim.getId());
        // The whole checklist must be COMPLETE: every default row (never
        // CANCELLED-out of existence) plus every follow-up row the adjuster
        // opened. Cancelling a row retires it from the checklist.
        List<Verification> open = history.stream()
                .filter(row -> !"CANCELLED".equals(row.getStatus()))
                .toList();
        List<String> incomplete = open.stream()
                .filter(row -> !"COMPLETE".equals(row.getStatus()))
                .map(row -> row.getType() == null ? "verification " + row.getId()
                        : row.getType().toLowerCase(java.util.Locale.ROOT) + " verification")
                .toList();
        if (open.isEmpty() || !incomplete.isEmpty()) {
            String missing = incomplete.isEmpty() ? "verification"
                    : String.join(", ", incomplete);
            throw new InvalidRequestException(
                    "Assessment needs every verification completed first — pending: "
                            + missing + ".");
        }
        List<ClaimCover> rows = claimCovers.findByClaimIdOrderByIdAsc(claim.getId());
        if (rows.isEmpty()) {
            throw new InvalidRequestException(
                    "This claim carries no covers to assess.");
        }
        if (input == null || input.covers() == null || input.covers().isEmpty()) {
            throw new InvalidRequestException("Assessment needs an amount per cover.");
        }
        String rationale = textOf(input.rationale());
        requireRationale(rationale, "A rationale is required to record an assessment.");
        Map<String, BigDecimal> assessedByCode = new HashMap<>();
        for (AssessmentInput.AssessedCover cover : input.covers()) {
            String code = cover == null || cover.coverCode() == null ? null
                    : cover.coverCode().trim().toUpperCase(java.util.Locale.ROOT);
            if (code == null || code.isEmpty() || cover.assessedAmount() == null) {
                throw new InvalidRequestException(
                        "Each assessed cover needs a cover code and an assessed amount.");
            }
            if (assessedByCode.put(code, cover.assessedAmount()) != null) {
                throw new InvalidRequestException(
                        "Cover " + code + " was assessed twice — assess each cover once.");
            }
        }
        Policy policy = policyOf(claim);
        Map<String, PolicyCover> opted = optedByCode(claim.getPolicyId());
        BigDecimal assessedTotal = BigDecimal.ZERO;
        for (ClaimCover row : rows) {
            BigDecimal assessed = assessedByCode.get(row.getCoverCode());
            if (assessed == null) {
                throw new InvalidRequestException(
                        "Assessment needs an amount for cover " + row.getCoverCode() + ".");
            }
            validateMoney(assessed, "Assessed amount for cover " + row.getCoverCode());
            if (assessed.compareTo(row.getClaimedAmount()) > 0) {
                throw new InvalidRequestException("Assessed amount for cover "
                        + row.getCoverCode() + " exceeds the claimed amount.");
            }
            PolicyCover cover = opted.get(row.getCoverCode());
            if (cover != null && cover.getSubLimit() != null
                    && assessed.compareTo(cover.getSubLimit()) > 0) {
                throw new InvalidRequestException("Assessed amount for cover "
                        + row.getCoverCode() + " exceeds the cover sub-limit of "
                        + cover.getSubLimit().toPlainString() + ".");
            }
            assessedTotal = assessedTotal.add(assessed);
        }
        // Policy-period cap: assessed on this claim + prior paid <= sum insured.
        BigDecimal sumInsured = policySumInsured(claim.getPolicyId());
        if (sumInsured != null) {
            BigDecimal priorPaid = priorNetPayables(claim.getPolicyId(), claim.getId());
            if (assessedTotal.add(priorPaid).compareTo(sumInsured) > 0) {
                BigDecimal remaining = sumInsured.subtract(priorPaid);
                throw new InvalidRequestException("Assessed total exceeds the remaining "
                        + "sum insured of " + remaining.toPlainString() + ".");
            }
        }
        for (ClaimCover row : rows) {
            row.setAssessedAmount(assessedByCode.get(row.getCoverCode()));
            claimCovers.save(row);
        }
        claim.setStage("DECISION");
        claims.save(claim);
        audit("ASSESSMENT_RECORDED", actorSub, claim,
                Map.of("status", "UNDER_REVIEW", "stage", "VERIFICATION"),
                after(claim, "stage", "DECISION", "assessedTotal", assessedTotal), rationale);
        return viewOf(claim, actorSub);
    }

    // --- cover decision ------------------------------------------------------------

    /**
     * V1 bridge: legacy no-cover claims keep the single-figure decision byte-identical —
     * the call delegates straight to {@link ClaimDecisionService} (including its
     * auto-escalation), so every existing journey passes unmodified. Claims with covers
     * take the staged path below.
     */
    @Transactional
    public Object decide(String claimNumber, String actorSub, CoverDecisionInput input) {
        Claim claim = claims.findByClaimNumberForUpdate(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!access.internalReaderMaySee(claim, actorSub, false)) {
            throw new ClaimNotFoundException();
        }
        List<ClaimCover> rows = claimCovers.findByClaimIdOrderByIdAsc(claim.getId());
        if (rows.isEmpty()) {
            ClaimDecisionInput legacy = new ClaimDecisionInput(
                    input == null ? null : input.decision(),
                    input == null ? null : input.indemnityAmount(),
                    input == null ? null : input.rationale());
            return legacyDecisions.decide(claimNumber, actorSub, legacy);
        }
        return decideWithCovers(claim, actorSub, input);
    }

    private StagedClaimView decideWithCovers(Claim claim, String actorSub,
            CoverDecisionInput input) {
        requireOpenUnderReview(claim);
        if (!"DECISION".equals(stageOf(claim))) {
            throw new InvalidRequestException(
                    "The decision is only available at the decision stage.");
        }
        if (input == null || input.covers() == null || input.covers().isEmpty()) {
            throw new InvalidRequestException("A decision needs an outcome per cover.");
        }
        String rationale = textOf(input.rationale());
        requireRationale(rationale, "A rationale is required.");
        AppUser actor = appUsers.findByKeycloakSub(actorSub)
                .orElseThrow(IllegalStateException::new);
        Policy policy = policyOf(claim);
        AuthorityConfig config = configOf(policy, claim.getClaimNumber());
        Map<String, PolicyCover> opted = optedByCode(claim.getPolicyId());
        List<ClaimCover> rows = claimCovers.findByClaimIdOrderByIdAsc(claim.getId());
        Map<String, CoverDecisionInput.CoverOutcome> byCode = new HashMap<>();
        for (CoverDecisionInput.CoverOutcome outcome : input.covers()) {
            String code = outcome == null || outcome.coverCode() == null ? null
                    : outcome.coverCode().trim().toUpperCase(java.util.Locale.ROOT);
            if (code == null || code.isEmpty()) {
                throw new InvalidRequestException("Each decided cover needs a cover code.");
            }
            if (byCode.put(code, outcome) != null) {
                throw new InvalidRequestException(
                        "Cover " + code + " was decided twice — decide each cover once.");
            }
        }
        Instant now = Instant.now();
        BigDecimal approvedTotal = BigDecimal.ZERO;
        BigDecimal netTotal = BigDecimal.ZERO;
        boolean anyApproved = false;
        boolean anyRejected = false;
        List<Map<String, Object>> applied = new ArrayList<>();
        for (ClaimCover row : rows) {
            CoverDecisionInput.CoverOutcome outcome = byCode.get(row.getCoverCode());
            if (outcome == null) {
                throw new InvalidRequestException(
                        "A decision needs an outcome for cover " + row.getCoverCode() + ".");
            }
            String coverDecision = outcome.decision();
            if (!"APPROVED".equals(coverDecision) && !"REJECTED".equals(coverDecision)) {
                throw new InvalidRequestException("Cover " + row.getCoverCode()
                        + " must be APPROVED or REJECTED.");
            }
            PolicyCover cover = opted.get(row.getCoverCode());
            BigDecimal assessed = row.getAssessedAmount();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("coverCode", row.getCoverCode());
            line.put("decision", coverDecision);
            if ("REJECTED".equals(coverDecision)) {
                String remarks = textOf(outcome.remarks());
                requireRationale(remarks,
                        "Remarks are required to reject cover " + row.getCoverCode() + ".");
                row.applyDecision("REJECTED", null,
                        row.getDeductibleAmount(), row.getAdjustmentAmount(), null,
                        remarks, actor.getId(), now, false);
                claimCovers.save(row);
                anyRejected = true;
                line.put("remarks", remarks);
                applied.add(line);
                continue;
            }
            if (assessed == null) {
                throw new InvalidRequestException("Cover " + row.getCoverCode()
                        + " has no assessed amount — assess it before deciding.");
            }
            BigDecimal approved = outcome.approvedAmount();
            if (approved == null) {
                throw new InvalidRequestException("Cover " + row.getCoverCode()
                        + " needs an approved amount.");
            }
            validateMoney(approved, "Approved amount for cover " + row.getCoverCode());
            if (approved.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidRequestException("Approved amount for cover "
                        + row.getCoverCode() + " must be greater than zero.");
            }
            if (approved.compareTo(assessed) > 0) {
                throw new InvalidRequestException("Approved amount for cover "
                        + row.getCoverCode() + " exceeds the assessed amount.");
            }
            if (approved.compareTo(row.getClaimedAmount()) > 0) {
                throw new InvalidRequestException("Approved amount for cover "
                        + row.getCoverCode() + " exceeds the claimed amount.");
            }
            if (cover != null && cover.getSubLimit() != null
                    && approved.compareTo(cover.getSubLimit()) > 0) {
                throw new InvalidRequestException("Approved amount for cover "
                        + row.getCoverCode() + " exceeds the cover sub-limit of "
                        + cover.getSubLimit().toPlainString() + ".");
            }
            BigDecimal deductible = outcome.deductibleAmount() != null
                    ? outcome.deductibleAmount()
                    : cover == null ? BigDecimal.ZERO : cover.getDeductibleDefault();
            BigDecimal adjustment = outcome.adjustmentAmount() != null
                    ? outcome.adjustmentAmount() : BigDecimal.ZERO;
            validateMoney(deductible, "Deductible for cover " + row.getCoverCode());
            validateMoney(adjustment.abs(), "Adjustment for cover " + row.getCoverCode());
            BigDecimal net = approved.subtract(deductible).add(adjustment);
            if (net.compareTo(BigDecimal.ZERO) < 0) {
                net = BigDecimal.ZERO;
            }
            row.applyDecision("PENDING", approved, deductible, adjustment, net,
                    textOf(outcome.remarks()), actor.getId(), now, true);
            claimCovers.save(row);
            approvedTotal = approvedTotal.add(approved);
            netTotal = netTotal.add(net);
            anyApproved = true;
            line.put("approvedAmount", approved);
            line.put("netPayable", net);
            applied.add(line);
        }
        // Pure rejection closes DENIED, never gated.
        if (!anyApproved && anyRejected) {
            for (ClaimCover row : claimCovers.findByClaimIdOrderByIdAsc(claim.getId())) {
                row.applyDecision("REJECTED", row.getApprovedAmount(),
                        row.getDeductibleAmount(), row.getAdjustmentAmount(),
                        row.getNetPayable(), row.getDecisionRemarks(), actor.getId(), now,
                        false);
                claimCovers.save(row);
            }
            claim.deny(rationale, now);
            claims.save(claim);
            metrics.decision("DENIED");
            audit("DECISION", actorSub, claim, Map.of("status", "UNDER_REVIEW"),
                    after(claim, "decision", "DENIED", "decisionRemarks", rationale),
                    rationale);
            Policy holderPolicy = policyOf(claim);
            ClaimDecisionView legacyView = new ClaimDecisionView(claim.getClaimNumber(),
                    claim.getDecision(), claim.getIndemnityAmount(),
                    claim.getDecisionRemarks(), null);
            outboxWriter.enqueueDecision(claim.getId(), holderPolicy.getHolderEmail(),
                    holderPolicy.getHolderName(), legacyView);
            return viewOf(claim, actorSub);
        }
        BigDecimal basisTotal = "NET_PAYABLE_TOTAL".equals(config.getAuthorityBasis())
                ? netTotal : approvedTotal;
        BigDecimal limit = limitFor(actor.getLevel(), config);
        if (limit != null && basisTotal.compareTo(limit) <= 0) {
            boolean mixed = anyRejected;
            // Finalize: approved rows APPROVED (proposal cleared), rejected rows
            // REJECTED (proposal cleared — the reject path above ran through the
            // proposal placeholder, so settle it here).
            for (ClaimCover row : claimCovers.findByClaimIdOrderByIdAsc(claim.getId())) {
                if (row.isProposal()) {
                    row.applyDecision(
                            row.getApprovedAmount() == null ? "REJECTED" : "APPROVED",
                            row.getApprovedAmount(), row.getDeductibleAmount(),
                            row.getAdjustmentAmount(), row.getNetPayable(),
                            row.getDecisionRemarks(), actor.getId(), now, false);
                    claimCovers.save(row);
                }
            }
            Instant closed = Instant.now();
            if (mixed) {
                claim.approvePartially(approvedTotal, closed);
            } else {
                claim.approve(approvedTotal, closed);
            }
            claims.save(claim);
            metrics.decision(mixed ? "PARTIALLY_APPROVED" : "APPROVED");
            payments.save(new Payment(claim.getId(), netTotal, actor.getId(), closed));
            audit("DECISION", actorSub, claim,
                    Map.of("status", "UNDER_REVIEW", "stage", "DECISION"),
                    after(claim, "decision", claim.getDecision(), "approvedTotal",
                            approvedTotal, "netPayableTotal", netTotal),
                    rationale);
            Policy holderPolicy = policyOf(claim);
            enqueueCoverDecision(holderPolicy, claim.getId(), claim.getClaimNumber(),
                    holderPolicy.getHolderName(), claim.getDecision(), approvedTotal,
                    netTotal, rationale);
            return viewOf(claim, actorSub);
        }
        // Above authority: proposals saved, the claim STAYS open at DECISION.
        audit("PROPOSALS_SAVED", actorSub, claim,
                Map.of("status", "UNDER_REVIEW", "stage", "DECISION"),
                after(claim, "proposedTotal", basisTotal, "authorityLimit", limit,
                        "authorityBasis", config.getAuthorityBasis()),
                rationale);
        return viewOf(claim, actorSub);
    }

    // --- refer --------------------------------------------------------------------

    @Transactional
    public ReferResult refer(String claimNumber, String actorSub, ReferInput input) {
        Claim claim = claims.findByClaimNumberForUpdate(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!access.internalReaderMaySee(claim, actorSub, false)) {
            throw new ClaimNotFoundException();
        }
        requireOpenUnderReview(claim);
        String reason = textOf(input == null ? null : input.reason());
        requireRationale(reason, "A reason is required to refer a claim.");
        AppUser actor = appUsers.findByKeycloakSub(actorSub)
                .orElseThrow(IllegalStateException::new);
        Policy policy = policyOf(claim);
        AuthorityConfig config = configOf(policy, claim.getClaimNumber());
        Long targetId = input == null ? null : input.targetAdjusterId();
        boolean auto = input != null && Boolean.TRUE.equals(input.auto());

        if ("DECISION".equals(stageOf(claim))) {
            return referWithProposals(claim, actor, policy, config, targetId, auto, reason,
                    actorSub);
        }
        return referDirect(claim, actor, targetId, auto, reason, actorSub);
    }

    /**
     * Pre-decision referral (REVIEW or VERIFICATION): no money has been assessed or
     * proposed, so there is nothing for a covering limit to clear against — the
     * claim moves to the named (or auto-picked) higher authority with its stage,
     * verifications and covers untouched.
     */
    private ReferResult referDirect(Claim claim, AppUser actor, Long targetId, boolean auto,
            String reason, String actorSub) {
        AppUser target = resolveDirectTarget(claim, actor, targetId, auto);
        Instant now = Instant.now();
        String previousLevel = claim.getLevel();
        Long previousAssignee = claim.getAssignedAdjusterId();
        if (target == null) {
            claim.escalateToSupervisor();
            claims.save(claim);
            metrics.escalation("SUPERVISOR");
            audit("CLAIM_REFERRED", actorSub, claim,
                    Map.of("status", "UNDER_REVIEW", "level", previousLevel,
                            "stage", stageOf(claim),
                            "assignedAdjusterId", previousAssignee == null ? 0
                                    : previousAssignee),
                    after(claim, "escalatedTo", "SUPERVISOR"),
                    reason);
            return new ReferResult(claim.getClaimNumber(), claim.getStatus(),
                    claim.getLevel(), null, "SUPERVISOR");
        }
        claim.setLevel(target.getLevel());
        assignTo(claim, target, now);
        claims.save(claim);
        metrics.escalation(target.getLevel());
        Map<String, Object> after = after(claim, "escalatedTo", target.getLevel());
        after.put("assignedAdjusterId", target.getId());
        after.put("assignedTo", target.getDisplayName());
        audit("CLAIM_REFERRED", actorSub, claim,
                Map.of("status", "UNDER_REVIEW", "level", previousLevel,
                        "stage", stageOf(claim),
                        "assignedAdjusterId",
                        previousAssignee == null ? 0 : previousAssignee),
                after, reason);
        return new ReferResult(claim.getClaimNumber(), claim.getStatus(), claim.getLevel(),
                target.getDisplayName(), target.getLevel());
    }

    /**
     * Resolves the pre-decision referral target: the named adjuster (must be a
     * higher rung), or the auto-pick — the least-loaded active adjuster one rung
     * up (skill-aware where the product has skill rows), falling back rung by
     * rung to L3. Null when no higher rung has a candidate (supervisor fallback).
     */
    private AppUser resolveDirectTarget(Claim claim, AppUser actor, Long targetId,
            boolean auto) {
        if (targetId != null) {
            AppUser target = appUsers.findById(targetId).orElse(null);
            if (target == null) {
                throw new InvalidRequestException("The named adjuster does not exist.");
            }
            if (rankOf(target.getLevel()) <= rankOf(actor.getLevel())) {
                throw new InvalidRequestException(
                        "Referral must go to a higher authority than your own.");
            }
            return target;
        }
        if (!auto) {
            throw new InvalidRequestException(
                    "Name a senior adjuster or choose automatic assignment.");
        }
        Policy policy = policyOf(claim);
        boolean productHasSkills =
                !skills.findByKeyProductCode(policy.getProductCode()).isEmpty();
        for (String rung : rungsAbove(actor.getLevel())) {
            List<AppUser> candidates = new ArrayList<>();
            for (AppUser adjuster : appUsers.findByLevelOrderById(rung)) {
                if (!isActive(adjuster.getId())) {
                    continue;
                }
                if (productHasSkills
                        && !skilledFor(adjuster.getId(), policy.getProductCode())) {
                    continue;
                }
                candidates.add(adjuster);
            }
            if (candidates.isEmpty()) {
                continue;
            }
            List<Long> ids = candidates.stream().map(AppUser::getId).toList();
            Long pick = LoadBalancer.leastLoaded(ids, openClaimCounts(ids));
            for (AppUser adjuster : candidates) {
                if (adjuster.getId().equals(pick)) {
                    return adjuster;
                }
            }
        }
        return null;
    }

    private static List<String> rungsAbove(String level) {
        if ("L2".equals(level)) {
            return List.of("L3");
        }
        if ("L3".equals(level)) {
            return List.of();
        }
        return List.of("L2", "L3");
    }

    /**
     * Decision-stage referral: the saved proposals travel (unchanged); the target
     * rung's limit must cover the proposed total (named), or the auto-pick finds
     * the lowest covering rung with a skilled candidate (supervisor fallback).
     */
    private ReferResult referWithProposals(Claim claim, AppUser actor, Policy policy,
            AuthorityConfig config, Long targetId, boolean auto, String reason,
            String actorSub) {

        List<ClaimCover> proposals = claimCovers.findByClaimIdOrderByIdAsc(claim.getId())
                .stream().filter(ClaimCover::isProposal).toList();
        if (proposals.isEmpty()) {
            throw new InvalidRequestException(
                    "There are no saved proposals to refer on this claim.");
        }
        BigDecimal basisTotal = proposalsBasisTotal(proposals, config.getAuthorityBasis());
        AppUser target = null;
        if (targetId != null) {
            target = appUsers.findById(targetId).orElse(null);
            if (target == null) {
                throw new InvalidRequestException("The named adjuster does not exist.");
            }
            if (rankOf(target.getLevel()) <= rankOf(actor.getLevel())) {
                throw new InvalidRequestException(
                        "Referral must go to a higher authority than your own.");
            }
            BigDecimal targetLimit = limitFor(target.getLevel(), config);
            if (targetLimit == null || basisTotal.compareTo(targetLimit) > 0) {
                throw new InvalidRequestException("The named adjuster's authority of "
                        + moneyOf(targetLimit) + " does not cover the proposed total of "
                        + basisTotal.toPlainString() + ".");
            }
        } else if (auto || targetId == null) {
            target = autoPick(policy.getProductCode(), basisTotal, config);
        }
        Instant now = Instant.now();
        Long previousAssignee = claim.getAssignedAdjusterId();
        String previousLevel = claim.getLevel();
        if (target == null) {
            claim.escalateToSupervisor();
            claims.save(claim);
            metrics.escalation("SUPERVISOR");
            audit("CLAIM_REFERRED", actorSub, claim,
                    Map.of("status", "UNDER_REVIEW", "level", previousLevel,
                            "assignedAdjusterId", previousAssignee == null ? 0
                                    : previousAssignee),
                    after(claim, "escalatedTo", "SUPERVISOR", "proposedTotal", basisTotal),
                    reason);
            return new ReferResult(claim.getClaimNumber(), claim.getStatus(),
                    claim.getLevel(), null, "SUPERVISOR");
        }
        claim.setLevel(target.getLevel());
        assignTo(claim, target, now);
        claims.save(claim);
        metrics.escalation(target.getLevel());
        Map<String, Object> after = after(claim, "escalatedTo", target.getLevel(),
                "proposedTotal", basisTotal);
        after.put("assignedAdjusterId", target.getId());
        after.put("assignedTo", target.getDisplayName());
        audit("CLAIM_REFERRED", actorSub, claim,
                Map.of("status", "UNDER_REVIEW", "level", previousLevel, "stage", "DECISION",
                        "assignedAdjusterId",
                        previousAssignee == null ? 0 : previousAssignee),
                after, reason);
        return new ReferResult(claim.getClaimNumber(), claim.getStatus(), claim.getLevel(),
                target.getDisplayName(), target.getLevel());
    }

    // --- NEED_INFO response (claimant) -----------------------------------------------

    @Transactional
    public ClaimantClaimView respondToNeedInfo(String claimNumber, String claimantSub,
            NeedInfoResponseInput input) {
        Claim claim = claims.findByClaimNumberForUpdate(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!claim.getClaimantSub().equals(claimantSub)) {
            throw new ClaimNotFoundException();
        }
        if (!"NEED_INFO".equals(claim.getStatus())) {
            throw new InvalidRequestException(
                    "This claim is not waiting on information from you.");
        }
        String priorStage = claim.getNeedInfoPriorStage() == null ? "REVIEW"
                : claim.getNeedInfoPriorStage();
        claim.setNeedInfoReason(null);
        claim.setNeedInfoPriorStage(null);
        claim.setStage(priorStage);
        AppUser pick = assigner.assign(claim);
        claims.save(claim);
        String message = textOf(input == null ? null : input.message());
        Map<String, Object> after = after(claim, "stage", priorStage);
        if (pick != null) {
            after.put("assignedAdjusterId", pick.getId());
            after.put("assignedTo", pick.getDisplayName());
        }
        audit("NEED_INFO_RESPONDED", claimantSub, claim, Map.of("status", "NEED_INFO"),
                after, message);
        return claimantViewOf(claim);
    }

    // --- supervisor escalated cover-decide ------------------------------------------

    /**
     * The supervisor half of the cover flow: decides a claim in
     * {@code ESCALATED_SUPERVISOR} with the same per-cover body (ungated, rationale
     * required, atomic close + single payment = Σ net). Legacy no-cover claims keep the
     * single-figure service path.
     */
    @Transactional
    public Object decideEscalation(String claimNumber, String actorSub,
            CoverDecisionInput input) {
        Claim claim = claims.findByClaimNumberForUpdate(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!"ESCALATED_SUPERVISOR".equals(claim.getStatus())) {
            if (claim.getDecision() != null) {
                throw new InvalidRequestException("This claim has already been decided.");
            }
            throw new InvalidRequestException(
                    "This claim is not awaiting a supervisor decision.");
        }
        List<ClaimCover> rows = claimCovers.findByClaimIdOrderByIdAsc(claim.getId());
        if (rows.isEmpty()) {
            ClaimDecisionInput legacy = new ClaimDecisionInput(
                    input == null ? null : input.decision(),
                    input == null ? null : input.indemnityAmount(),
                    input == null ? null : input.rationale());
            // Supervisor path stays on the escalation service (ungated legacy close).
            return supervisorLegacyDecide(claimNumber, actorSub, legacy);
        }
        return decideEscalationWithCovers(claim, actorSub, input);
    }

    // --- internals -------------------------------------------------------------------

    private ClaimDecisionOutcome supervisorLegacyDecide(String claimNumber, String actorSub,
            ClaimDecisionInput legacy) {
        // The escalation service owns the locked supervisor-only legacy close; this
        // method runs inside the supervisor-only route, so delegation is safe.
        return escalationDecisions.decideEscalation(claimNumber, actorSub, legacy);
    }

    private StagedClaimView decideEscalationWithCovers(Claim claim, String actorSub,
            CoverDecisionInput input) {
        if (input == null || input.covers() == null || input.covers().isEmpty()) {
            throw new InvalidRequestException("A decision needs an outcome per cover.");
        }
        String rationale = textOf(input.rationale());
        requireRationale(rationale, "A rationale is required.");
        Map<String, PolicyCover> opted = optedByCode(claim.getPolicyId());
        Map<String, CoverDecisionInput.CoverOutcome> byCode = new HashMap<>();
        for (CoverDecisionInput.CoverOutcome outcome : input.covers()) {
            String code = outcome == null || outcome.coverCode() == null ? null
                    : outcome.coverCode().trim().toUpperCase(java.util.Locale.ROOT);
            if (code == null || code.isEmpty()) {
                throw new InvalidRequestException("Each decided cover needs a cover code.");
            }
            if (byCode.put(code, outcome) != null) {
                throw new InvalidRequestException(
                        "Cover " + code + " was decided twice — decide each cover once.");
            }
        }
        Instant now = Instant.now();
        BigDecimal approvedTotal = BigDecimal.ZERO;
        BigDecimal netTotal = BigDecimal.ZERO;
        boolean anyApproved = false;
        boolean anyRejected = false;
        List<ClaimCover> rows = claimCovers.findByClaimIdOrderByIdAsc(claim.getId());
        for (ClaimCover row : rows) {
            CoverDecisionInput.CoverOutcome outcome = byCode.get(row.getCoverCode());
            if (outcome == null) {
                throw new InvalidRequestException(
                        "A decision needs an outcome for cover " + row.getCoverCode() + ".");
            }
            String coverDecision = outcome.decision();
            if (!"APPROVED".equals(coverDecision) && !"REJECTED".equals(coverDecision)) {
                throw new InvalidRequestException("Cover " + row.getCoverCode()
                        + " must be APPROVED or REJECTED.");
            }
            PolicyCover cover = opted.get(row.getCoverCode());
            if ("REJECTED".equals(coverDecision)) {
                String remarks = textOf(outcome.remarks());
                requireRationale(remarks,
                        "Remarks are required to reject cover " + row.getCoverCode() + ".");
                row.applyDecision("REJECTED", null, row.getDeductibleAmount(),
                        row.getAdjustmentAmount(), null, remarks, null, now, false);
                claimCovers.save(row);
                anyRejected = true;
                continue;
            }
            BigDecimal approved = outcome.approvedAmount();
            if (approved == null) {
                throw new InvalidRequestException("Cover " + row.getCoverCode()
                        + " needs an approved amount.");
            }
            validateMoney(approved, "Approved amount for cover " + row.getCoverCode());
            if (approved.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidRequestException("Approved amount for cover "
                        + row.getCoverCode() + " must be greater than zero.");
            }
            if (row.getAssessedAmount() != null
                    && approved.compareTo(row.getAssessedAmount()) > 0) {
                throw new InvalidRequestException("Approved amount for cover "
                        + row.getCoverCode() + " exceeds the assessed amount.");
            }
            if (cover != null && cover.getSubLimit() != null
                    && approved.compareTo(cover.getSubLimit()) > 0) {
                throw new InvalidRequestException("Approved amount for cover "
                        + row.getCoverCode() + " exceeds the cover sub-limit of "
                        + cover.getSubLimit().toPlainString() + ".");
            }
            BigDecimal deductible = outcome.deductibleAmount() != null
                    ? outcome.deductibleAmount()
                    : cover == null ? BigDecimal.ZERO : cover.getDeductibleDefault();
            BigDecimal adjustment = outcome.adjustmentAmount() != null
                    ? outcome.adjustmentAmount() : BigDecimal.ZERO;
            validateMoney(deductible, "Deductible for cover " + row.getCoverCode());
            validateMoney(adjustment.abs(), "Adjustment for cover " + row.getCoverCode());
            BigDecimal net = approved.subtract(deductible).add(adjustment);
            if (net.compareTo(BigDecimal.ZERO) < 0) {
                net = BigDecimal.ZERO;
            }
            row.applyDecision("APPROVED", approved, deductible, adjustment, net,
                    textOf(outcome.remarks()), null, now, false);
            claimCovers.save(row);
            approvedTotal = approvedTotal.add(approved);
            netTotal = netTotal.add(net);
            anyApproved = true;
        }
        if (!anyApproved && anyRejected) {
            claim.deny(rationale, now);
            claims.save(claim);
            metrics.decision("DENIED");
            audit("DECISION", actorSub, claim, Map.of("status", "ESCALATED_SUPERVISOR"),
                    after(claim, "decision", "DENIED", "decisionRemarks", rationale),
                    rationale);
            Policy holderPolicy = policyOf(claim);
            outboxWriter.enqueueDecision(claim.getId(), holderPolicy.getHolderEmail(),
                    holderPolicy.getHolderName(), new ClaimDecisionView(
                            claim.getClaimNumber(), claim.getDecision(),
                            claim.getIndemnityAmount(), claim.getDecisionRemarks(), null));
            return viewOf(claim, actorSub);
        }
        if (anyRejected) {
            claim.approvePartially(approvedTotal, now);
        } else {
            claim.approve(approvedTotal, now);
        }
        claims.save(claim);
        metrics.decision(anyRejected ? "PARTIALLY_APPROVED" : "APPROVED");
        payments.save(new Payment(claim.getId(), netTotal, null, now));
        audit("DECISION", actorSub, claim, Map.of("status", "ESCALATED_SUPERVISOR"),
                after(claim, "decision", claim.getDecision(), "approvedTotal", approvedTotal,
                        "netPayableTotal", netTotal),
                rationale);
        Policy holderPolicy = policyOf(claim);
        outboxWriter.enqueueDecision(claim.getId(), holderPolicy.getHolderEmail(),
                holderPolicy.getHolderName(), new ClaimDecisionView(
                        claim.getClaimNumber(), claim.getDecision(), approvedTotal, null,
                        null, null, null, null));
        return viewOf(claim, actorSub);
    }

    // --- view assembly ---------------------------------------------------------------

    private StagedClaimView viewOf(Claim claim, String actorSub) {
        Policy policy = policyOf(claim);
        AuthorityConfig config = configOf(policy, claim.getClaimNumber());
        AppUser actor = appUsers.findByKeycloakSub(actorSub).orElse(null);
        BigDecimal limit = actor == null ? null
                : limitFor(actor.getLevel(), config);
        Map<String, PolicyCover> opted = optedByCode(claim.getPolicyId());
        List<StagedClaimView.StagedCoverView> covers = new ArrayList<>();
        for (ClaimCover row : claimCovers.findByClaimIdOrderByIdAsc(claim.getId())) {
            PolicyCover cover = opted.get(row.getCoverCode());
            BigDecimal subLimit = cover == null ? null : cover.getSubLimit();
            covers.add(new StagedClaimView.StagedCoverView(row.getCoverCode(),
                    cover == null ? row.getCoverCode() : cover.getDisplayName(),
                    row.getClaimedAmount(), subLimit,
                    subLimit != null && row.getClaimedAmount() != null
                            && row.getClaimedAmount().compareTo(subLimit) > 0,
                    row.getAssessedAmount(), row.getApprovedAmount(),
                    row.getDeductibleAmount(), row.getAdjustmentAmount(),
                    row.getNetPayable(), row.getDecision(), row.getDecisionRemarks(),
                    row.isProposal()));
        }
        List<StagedClaimView.VerificationView> history = verifications
                .findByClaimIdOrderByIdAsc(claim.getId()).stream()
                .map(this::verificationView).toList();
        boolean anyProposal = covers.stream().anyMatch(StagedClaimView.StagedCoverView::proposal);
        BigDecimal proposedTotal = anyProposal
                ? proposalsViewTotal(claim.getId(), config.getAuthorityBasis()) : null;
        int[] docCounts = requiredDocuments.counts(claim.getId());
        return new StagedClaimView(claim.getClaimNumber(), claim.getStatus(),
                stageOf(claim), claim.getLevel(), policy.getPolicyNumber(),
                policy.getProductCode(), coverageOf(policy.getId()), policy.getHolderName(),
                claim.getLossDate(), claim.getLossLocation(), claim.getLossDescription(),
                claim.getClaimantRemarks(), claim.getReserveAmount(),
                displayNameOf(claim.getAssignedAdjusterId()), claim.getNeedInfoReason(),
                claim.getNeedInfoPriorStage(), covers.isEmpty() ? null : covers,
                claim.getClaimedTotal(), history.isEmpty() ? null : history, limit,
                config.getAuthorityBasis(), anyProposal ? Boolean.TRUE : null,
                proposedTotal, docCounts[0], docCounts[1]);
    }

    private StagedClaimView.VerificationView verificationView(Verification row) {
        return new StagedClaimView.VerificationView(row.getId(), row.getType(),
                row.getStatus(), row.getOutcome(), row.getNotes(), row.getEvidenceRefs(),
                row.getPerformedBy(), row.getStartedAt(), row.getCompletedAt());
    }

    private ClaimantClaimView claimantViewOf(Claim claim) {
        Map<String, PolicyCover> byCode = optedByCode(claim.getPolicyId());
        List<ClaimCover> rows = claimCovers.findByClaimIdOrderByIdAsc(claim.getId());
        RequiredDocumentService.ClaimantDocs docs =
                requiredDocuments.claimantDocs(claim.getId());
        if (rows.isEmpty()) {
            return ClaimantClaimView.from(claim, null, null, null,
                    docs.received(), docs.total(), docs.items());
        }
        List<ClaimantCoverView> covers = new ArrayList<>();
        BigDecimal netTotal = BigDecimal.ZERO;
        boolean anyNet = false;
        for (ClaimCover row : rows) {
            PolicyCover cover = byCode.get(row.getCoverCode());
            covers.add(new ClaimantCoverView(row.getCoverCode(),
                    cover == null ? row.getCoverCode() : cover.getDisplayName(),
                    row.getClaimedAmount(),
                    cover == null ? null : cover.getSubLimit(),
                    cover != null && row.getClaimedAmount() != null
                            && cover.getSubLimit() != null
                            && row.getClaimedAmount().compareTo(cover.getSubLimit()) > 0,
                    row.getDecision(),
                    "APPROVED".equals(row.getDecision()) ? row.getApprovedAmount() : null,
                    row.getDecisionRemarks()));
            if ("APPROVED".equals(row.getDecision()) && row.getNetPayable() != null) {
                netTotal = netTotal.add(row.getNetPayable());
                anyNet = true;
            }
        }
        String decision = claim.getDecision();
        BigDecimal paymentFigure = null;
        if ("APPROVED".equals(decision) || "PARTIALLY_APPROVED".equals(decision)) {
            paymentFigure = anyNet ? netTotal : claim.getIndemnityAmount();
        }
        return ClaimantClaimView.from(claim, covers, claim.getClaimedTotal(),
                "CLOSED".equals(claim.getStatus()) ? paymentFigure : null,
                docs.received(), docs.total(), docs.items());
    }

    // --- guards + lookups ---------------------------------------------------------------

    private Claim requireAssignee(String claimNumber, String actorSub, boolean supervisor) {
        Claim claim = claims.findByClaimNumber(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!access.internalReaderMaySee(claim, actorSub, supervisor)) {
            throw new ClaimNotFoundException();
        }
        return claim;
    }

    private Claim requireAssigneeForUpdate(String claimNumber, String actorSub,
            boolean supervisor) {
        Claim claim = claims.findByClaimNumberForUpdate(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!access.internalReaderMaySee(claim, actorSub, supervisor)) {
            throw new ClaimNotFoundException();
        }
        return claim;
    }

    private static void requireOpenUnderReview(Claim claim) {
        if ("CLOSED".equals(claim.getStatus()) || claim.getDecision() != null) {
            throw new InvalidRequestException("This claim has already been decided.");
        }
        if ("NEED_INFO".equals(claim.getStatus())) {
            throw new InvalidRequestException(
                    "This claim is waiting on information from the claimant.");
        }
        if ("ESCALATED_SUPERVISOR".equals(claim.getStatus())) {
            throw new InvalidRequestException(
                    "This claim is awaiting a supervisor decision.");
        }
        if (!"UNDER_REVIEW".equals(claim.getStatus())) {
            throw new InvalidRequestException("This claim is not under review.");
        }
    }

    private static String stageOf(Claim claim) {
        return claim.getStage() == null ? "REVIEW" : claim.getStage();
    }

    private Policy policyOf(Claim claim) {
        return policies.findById(claim.getPolicyId())
                .orElseThrow(() -> new IllegalStateException(
                        "Claim " + claim.getClaimNumber() + " references a missing policy "
                                + claim.getPolicyId()));
    }

    private AuthorityConfig configOf(Policy policy, String claimNumber) {
        return authorityConfigs.findAll().stream()
                .filter(c -> c.getProductCode().equals(policy.getProductCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No authority config exists for product " + policy.getProductCode()
                                + " (claim " + claimNumber + ")"));
    }

    private Map<String, PolicyCover> optedByCode(Long policyId) {
        Map<String, PolicyCover> byCode = new HashMap<>();
        for (PolicyCover cover : policyCovers
                .findByPolicyIdOrderBySortOrderAscIdAsc(policyId)) {
            byCode.put(cover.getCoverCode(), cover);
        }
        return byCode;
    }

    private BigDecimal policySumInsured(Long policyId) {
        return jdbcTemplate.queryForObject(
                "SELECT sum_insured FROM policy WHERE id = ?", BigDecimal.class, policyId);
    }

    /**
     * Prior paid on the policy: Σ net_payable of non-rejected cover rows on OTHER
     * claims of the same policy (derived, never stored).
     */
    private BigDecimal priorNetPayables(Long policyId, Long excludeClaimId) {
        BigDecimal paid = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(cc.net_payable), 0) FROM claim_cover cc "
                        + "JOIN claim c ON c.id = cc.claim_id "
                        + "WHERE c.policy_id = ? AND cc.claim_id <> ? "
                        + "AND cc.net_payable IS NOT NULL AND cc.decision <> 'REJECTED'",
                BigDecimal.class, policyId, excludeClaimId);
        return paid == null ? BigDecimal.ZERO : paid;
    }

    private static BigDecimal limitFor(String level, AuthorityConfig config) {
        if ("L3".equals(level)) {
            return config.getL3LimitAmount();
        }
        if ("L2".equals(level)) {
            return config.getL2LimitAmount();
        }
        return config.getL1LimitAmount();
    }

    private static int rankOf(String level) {
        if ("L3".equals(level)) {
            return 3;
        }
        if ("L2".equals(level)) {
            return 2;
        }
        return 1;
    }

    private BigDecimal proposalsBasisTotal(List<ClaimCover> proposals, String basis) {
        BigDecimal total = BigDecimal.ZERO;
        for (ClaimCover row : proposals) {
            BigDecimal part = "NET_PAYABLE_TOTAL".equals(basis) ? row.getNetPayable()
                    : row.getApprovedAmount();
            if (part != null) {
                total = total.add(part);
            }
        }
        return total;
    }

    private BigDecimal proposalsViewTotal(Long claimId, String basis) {
        List<ClaimCover> proposals = claimCovers.findByClaimIdOrderByIdAsc(claimId).stream()
                .filter(ClaimCover::isProposal).toList();
        if (proposals.isEmpty()) {
            return null;
        }
        return proposalsBasisTotal(proposals, basis);
    }

    /**
     * Auto-pick: least-loaded active adjuster at the lowest rung whose limit covers the
     * total and who is skilled for the product (skill rows win; when the product has no
     * skill rows at all, any adjuster of the rung qualifies). Null when no rung covers
     * it — the caller escalates to the supervisor.
     */
    private AppUser autoPick(String productCode, BigDecimal total, AuthorityConfig config) {
        List<String> rungs = List.of("L1", "L2", "L3");
        boolean productHasSkills = !skills.findByKeyProductCode(productCode).isEmpty();
        for (String rung : rungs) {
            BigDecimal limit = limitFor(rung, config);
            if (limit == null || total.compareTo(limit) > 0) {
                continue;
            }
            List<AppUser> candidates = new ArrayList<>();
            for (AppUser adjuster : appUsers.findByLevelOrderById(rung)) {
                if (!isActive(adjuster.getId())) {
                    continue;
                }
                if (productHasSkills && !skilledFor(adjuster.getId(), productCode)) {
                    continue;
                }
                candidates.add(adjuster);
            }
            if (candidates.isEmpty()) {
                continue;
            }
            List<Long> ids = candidates.stream().map(AppUser::getId).toList();
            Long pick = LoadBalancer.leastLoaded(ids, openClaimCounts(ids));
            if (pick == null) {
                continue;
            }
            for (AppUser adjuster : candidates) {
                if (adjuster.getId().equals(pick)) {
                    return adjuster;
                }
            }
        }
        return null;
    }

    private boolean isActive(Long adjusterId) {
        Boolean active = jdbcTemplate.queryForObject(
                "SELECT active FROM app_user WHERE id = ?", Boolean.class, adjusterId);
        return !Boolean.FALSE.equals(active);
    }

    private boolean skilledFor(Long adjusterId, String productCode) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM adjuster_skill WHERE adjuster_id = ? AND product_code = ?",
                Long.class, adjusterId, productCode);
        return count != null && count > 0;
    }

    private Map<Long, Long> openClaimCounts(List<Long> candidateIds) {
        Map<Long, Long> counts = new HashMap<>();
        String placeholders = String.join(",",
                candidateIds.stream().map(id -> "?").toList());
        jdbcTemplate.query(
                "SELECT assigned_adjuster_id, COUNT(*) FROM claim "
                        + "WHERE assigned_adjuster_id IN (" + placeholders + ") "
                        + "AND status <> 'CLOSED' "
                        + "GROUP BY assigned_adjuster_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> counts.put(
                        rs.getLong(1), rs.getLong(2)),
                candidateIds.toArray());
        return counts;
    }

    private void assignTo(Claim claim, AppUser adjuster, Instant at) {
        jdbcTemplate.queryForList(
                "SELECT id FROM app_user WHERE level = ? ORDER BY id FOR UPDATE", Long.class,
                adjuster.getLevel());
        claim.setLevel(adjuster.getLevel());
        claim.setStatus("UNDER_REVIEW");
        claim.setAssignedAdjusterId(adjuster.getId(), at);
    }

    private String displayNameOf(Long appUserId) {
        if (appUserId == null) {
            return null;
        }
        return appUsers.findById(appUserId).map(AppUser::getDisplayName).orElse(null);
    }

    private Object coverageOf(Long policyId) {
        String text = jdbcTemplate.queryForObject(
                "SELECT coverage::text FROM policy WHERE id = ?", String.class, policyId);
        try {
            return text == null ? null : JSON.readTree(text);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Policy " + policyId + " holds invalid coverage JSON",
                    ex);
        }
    }

    private static String textOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void requireRationale(String rationale, String message) {
        if (rationale == null) {
            throw new InvalidRequestException(message);
        }
    }

    private static void validateMoney(BigDecimal amount, String what) {
        if (amount == null) {
            throw new InvalidRequestException(what + " is required.");
        }
        if (amount.scale() > 2) {
            throw new InvalidRequestException(what + " may have at most 2 decimal places.");
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidRequestException(what + " must be zero or more.");
        }
        if (amount.compareTo(MAX_MONEY) > 0) {
            throw new InvalidRequestException(
                    what + " is too large (maximum 999999999999.99).");
        }
    }

    private static String moneyOf(BigDecimal amount) {
        return amount == null ? "no configured limit" : amount.toPlainString();
    }

    private void audit(String action, String actorSub, Claim claim,
            Map<String, Object> before, Map<String, Object> after, String rationale) {
        auditLog.append(actorSub, action, "CLAIM", claim.getId(), AuditJson.of(before),
                AuditJson.of(after), rationale);
    }

    /**
     * Cover-closure notice: the outbox stores the verbatim subject/body the claimant
     * receives, composed here so the wording survives a {@link ClaimDecisionView}
     * whose single-figure fields cannot express a mixed outcome (partial approval
     * pays Σ net, not Σ approved). The stored row keeps the legacy subject
     * ("Decision on claim …") so Mailpit/content assertions stay stable.
     */
    private void enqueueCoverDecision(Policy policy, Long claimId, String claimNumber,
            String holderName, String decision, BigDecimal approvedTotal, BigDecimal netTotal,
            String rationale) {
        String subject = "Decision on claim " + claimNumber;
        String body;
        if ("DENIED".equals(decision)) {
            body = """
                    Dear %s,

                    Your claim %s has not been approved.

                    %s

                    Yours,
                    Claims Processing
                    """.formatted(holderName, claimNumber, rationale == null ? "" : rationale);
        } else if ("PARTIALLY_APPROVED".equals(decision)) {
            body = """
                    Dear %s,

                    Your claim %s has been partially approved. We will pay %s.

                    Yours,
                    Claims Processing
                    """.formatted(holderName, claimNumber, pounds(netTotal));
        } else {
            body = """
                    Dear %s,

                    Your claim %s has been approved. We will pay %s.

                    Yours,
                    Claims Processing
                    """.formatted(holderName, claimNumber, pounds(netTotal));
        }
        jdbcTemplate.update(
                "INSERT INTO email_outbox (claim_id, kind, to_address, subject, body) "
                        + "VALUES (?, 'DECISION', ?, ?, ?)",
                claimId, policy.getHolderEmail(), subject, body);
    }

    private static String pounds(BigDecimal amount) {
        return amount == null ? "" : "£" + amount.toPlainString();
    }

    private Map<String, Object> after(Claim claim, Object... pairs) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("claimNumber", claim.getClaimNumber());
        after.put("status", claim.getStatus());
        after.put("stage", stageOf(claim));
        after.put("level", claim.getLevel());
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            after.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return after;
    }

    /** Small outcome for the refer endpoint (resolved by its controller to a view). */
    public record ReferResult(String claimNumber, String status, String level,
            String assignedTo, String escalatedTo) {
    }
}
