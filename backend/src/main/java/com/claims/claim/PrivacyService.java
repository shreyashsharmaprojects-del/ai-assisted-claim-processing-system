package com.claims.claim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.api.InvalidRequestException;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;

/**
 * V24 (V3 S9: GDPR + retention story).
 *
 * <p>Three bounded operations, nothing more:
 * <ul>
 * <li>export — the caller's own policies + claims + covers + attachment
 * <i>metadata</i> (never bytes: no storage reads) + timeline milestones;</li>
 * <li>anonymize — supervisor-only erasure of one subject's PII on exactly the
 * listed columns, refusing shared policies with a 400;</li>
 * <li>retention-report — counts of CLOSED claims past fixed 6/7/10-year windows
 * (report-only, no deletes).</li>
 * </ul>
 *
 * <p>Boundaries, enforced by construction: the export walks only the caller's
 * own claims (claimant wall); anonymize never touches {@code audit_log} (the
 * V7 trigger would reject it anyway), payment amounts, decision outcomes/remarks
 * or claim descriptions — those columns appear in no UPDATE below.
 */
@Service
public class PrivacyService {

    private final ClaimRepository claims;
    private final ClaimCoverRepository claimCovers;
    private final AttachmentRepository attachments;
    private final InternalNoteRepository notes;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogWriter auditLog;
    private final int retentionClosedYears;

    public PrivacyService(ClaimRepository claims, ClaimCoverRepository claimCovers,
            AttachmentRepository attachments, InternalNoteRepository notes,
            JdbcTemplate jdbcTemplate, AuditLogWriter auditLog,
            @Value("${claims.retention.closed-years:7}") int retentionClosedYears) {
        this.claims = claims;
        this.claimCovers = claimCovers;
        this.attachments = attachments;
        this.notes = notes;
        this.jdbcTemplate = jdbcTemplate;
        this.auditLog = auditLog;
        this.retentionClosedYears = retentionClosedYears;
    }

    /** First 8 hex chars of SHA-256(sub): the stable anonymization key. */
    public static String sha8(String sub) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sub.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * The caller's own data as a JSON-serializable map — policies (as filed on the
     * subject's claims; redacted rows excluded since the holder link is gone) +
     * claims + covers + attachment metadata (never bytes) + timeline milestones
     * (audit CLAIM rows: at/actor/action — the internal feed's own milestone set).
     * Own-sub only: every row is keyed off the caller's subject, so another
     * subject's data can never enter the payload.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> exportOwn(String claimantSub) {
        List<Claim> own = claims.findByClaimantSubOrderByIdAsc(claimantSub);
        List<Map<String, Object>> policyViews = new ArrayList<>();
        for (Long policyId : claims.findDistinctPolicyIdsByClaimantSub(claimantSub)) {
            Map<String, Object> policy = jdbcTemplate.queryForMap(
                    "SELECT policy_number, product_code, holder_name, holder_email, status, "
                            + "sum_insured, valid_from, valid_to FROM policy WHERE id = ?",
                    policyId);
            // An erased row no longer links to this subject (holder re-keyed to the
            // ANON: address); exporting it would leak another handling's key.
            if (isRedactedHolder(policy)) {
                continue;
            }
            policyViews.add(policy);
        }
        List<Map<String, Object>> claimViews = new ArrayList<>();
        List<Map<String, Object>> coverViews = new ArrayList<>();
        List<Map<String, Object>> attachmentViews = new ArrayList<>();
        List<Map<String, Object>> milestones = new ArrayList<>();
        for (Claim claim : own) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("claimNumber", claim.getClaimNumber());
            row.put("policyId", claim.getPolicyId());
            row.put("status", claim.getStatus());
            row.put("stage", claim.getStage());
            row.put("level", claim.getLevel());
            row.put("lossDate", String.valueOf(claim.getLossDate()));
            row.put("lossLocation", claim.getLossLocation());
            row.put("lossDescription", claim.getLossDescription());
            row.put("claimantRemarks", claim.getClaimantRemarks());
            row.put("decision", claim.getDecision());
            row.put("decisionRemarks", claim.getDecisionRemarks());
            row.put("indemnityAmount", claim.getIndemnityAmount());
            row.put("claimedTotal", claim.getClaimedTotal());
            row.put("closedAt", claim.getClosedAt() == null ? null
                    : claim.getClosedAt().toString());
            claimViews.add(row);
            for (ClaimCover coverRow : claimCovers.findByClaimIdOrderByIdAsc(claim.getId())) {
                Map<String, Object> cover = new LinkedHashMap<>();
                cover.put("claimNumber", claim.getClaimNumber());
                cover.put("coverCode", coverRow.getCoverCode());
                cover.put("claimedAmount", coverRow.getClaimedAmount());
                cover.put("decision", coverRow.getDecision());
                cover.put("decisionRemarks", coverRow.getDecisionRemarks());
                cover.put("approvedAmount", coverRow.getApprovedAmount());
                cover.put("netPayable", coverRow.getNetPayable());
                coverViews.add(cover);
            }
            for (Attachment attachment : attachments
                    .findByClaimIdOrderById(claim.getId())) {
                // Metadata only: original name, type, size, sha, label, doc type —
                // never bytes (no storage read happens on this path).
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("claimNumber", claim.getClaimNumber());
                meta.put("originalName", attachment.getOriginalName());
                meta.put("label", attachment.getLabel());
                meta.put("contentType", attachment.getContentType());
                meta.put("sizeBytes", attachment.getSizeBytes());
                meta.put("sha256", attachment.getSha256());
                meta.put("docType", attachment.getDocType());
                attachmentViews.add(meta);
            }
            milestones.addAll(jdbcTemplate.query(
                    "SELECT action, actor_sub, rationale, created_at FROM audit_log "
                            + "WHERE entity_type = 'CLAIM' AND entity_id = ? ORDER BY id",
                    (rs, n) -> {
                        Map<String, Object> milestone = new LinkedHashMap<>();
                        milestone.put("claimNumber", claim.getClaimNumber());
                        milestone.put("action", rs.getString("action"));
                        milestone.put("actorSub", rs.getString("actor_sub"));
                        milestone.put("rationale", rs.getString("rationale"));
                        milestone.put("at", String.valueOf(rs.getObject("created_at")));
                        return milestone;
                    }, claim.getId()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subject", claimantSub);
        payload.put("policies", policyViews);
        payload.put("claims", claimViews);
        payload.put("covers", coverViews);
        payload.put("attachments", attachmentViews);
        payload.put("milestones", milestones);
        return payload;
    }

    /**
     * Supervisor-only erasure of one subject's PII — overwrites exactly:
     * policy holder name/email (single-owner policies only), claim
     * claimant_sub/remarks, subject-authored internal-note bodies (row + author
     * kept), and matching attachment uploader links. Never: audit_log, payment
     * amounts, decision outcomes/remarks, claim descriptions.
     *
     * <p>Idempotent: every overwrite is stable (REDACTED stays REDACTED,
     * ANON:&lt;sha8&gt; stays ANON:&lt;sha8&gt;), so a second run finds either
     * the same already-redacted rows (matched by both original sub and the ANON:
     * key) or zero remaining rows for the original sub — and still writes the
     * ERASURE row + audit, returning ok.
     */
    @Transactional
    public Map<String, Object> anonymize(String claimantSub, String rationale,
            String supervisorSub) {
        if (claimantSub == null || claimantSub.isBlank()) {
            throw new InvalidRequestException(
                    "claimantSub is required.");
        }
        if (rationale == null || rationale.isBlank()) {
            throw new InvalidRequestException(
                    "rationale is required.");
        }
        String anon = "ANON:" + sha8(claimantSub);
        String anonEmail = "redacted+" + sha8(claimantSub) + "@example.invalid";

        // Shared-policy guard: any policy this subject claimed on that also
        // carries another subject's claim is 400 with an explanation — redacting
        // the shared holder row would destroy a living subject's PII.
        List<Long> policyIds = claims.findDistinctPolicyIdsByClaimantSub(claimantSub);
        List<String> shared = new ArrayList<>();
        for (Long policyId : policyIds) {
            if (claims.countDistinctClaimantSubsByPolicyId(policyId) > 1) {
                String policyNumber = jdbcTemplate.queryForObject(
                        "SELECT policy_number FROM policy WHERE id = ?", String.class,
                        policyId);
                shared.add(policyNumber);
            }
        }
        if (!shared.isEmpty()) {
            throw new InvalidRequestException(
                    "Cannot anonymize: policy " + String.join(", ", shared)
                            + " is shared with other claimant(s); redacting the shared "
                            + "holder record would erase another subject's data. "
                            + "Split the policy or handle it manually.");
        }

        // Only the listed columns — every UPDATE below names its columns; payment
        // amounts, decisions, descriptions and audit_log appear nowhere here.
        // Both the live sub and the ANON: key match, so re-runs are stable.
        // The policy rows come from the subject's own claims (claim.policy_id),
        // not from a holder-email lookup: after a first erasure the email no
        // longer matches the subject, and re-resolving by email would miss rows.
        for (Long policyId : policyIds) {
            jdbcTemplate.update(
                    "UPDATE policy SET holder_name = 'REDACTED', "
                            + "holder_email = ? WHERE id = ? "
                            + "AND holder_email NOT LIKE 'redacted+%@example.invalid'",
                    anonEmail, policyId);
        }
        List<Claim> own = claims.findByClaimantSubOrderByIdAsc(claimantSub);
        for (Claim claim : own) {
            Long claimId = claim.getId();
            jdbcTemplate.update(
                    "UPDATE claim SET claimant_sub = ?, claimant_remarks = NULL "
                            + "WHERE id = ? AND (claimant_sub = ? OR claimant_sub = ?)",
                    anon, claimId, claimantSub, anon);
            jdbcTemplate.update(
                    "UPDATE internal_note SET body = '[redacted]' "
                            + "WHERE claim_id = ? AND author_sub = ? "
                            + "AND body <> '[redacted]'",
                    claimId, claimantSub);
            jdbcTemplate.update(
                    "UPDATE attachment SET uploaded_by_sub = NULL "
                            + "WHERE claim_id = ? AND uploaded_by_sub = ?",
                    claimId, claimantSub);
        }

        jdbcTemplate.update(
                "INSERT INTO privacy_request (claimant_sub, kind, status, handled_by) "
                        + "VALUES (?, 'ERASURE', 'COMPLETED', ?)",
                claimantSub, supervisorSub);
        auditLog.append(supervisorSub, "PRIVACY_ERASURE", "PRIVACY", null, null,
                AuditJson.of(Map.of("claimantSub", anon, "claimsAnonymized", own.size(),
                        "policiesRedacted", policyIds.size())),
                rationale.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("anonymizedSub", anon);
        result.put("claimsAnonymized", own.size());
        result.put("policiesRedacted", policyIds.size());
        return result;
    }

    /**
     * Report-only counts of CLOSED claims with closed_at older than the 6, 7 and
     * 10-year windows (fixed buckets per slice). The middle bucket mirrors the
     * {@code claims.retention.closed-years} policy value (default 7); the config
     * key documents the policy window, the report never deletes. Unknown claims
     * surface: closed_at NULL rows are excluded (never closed).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> retentionReport() {
        Instant now = Instant.now();
        Map<String, Object> buckets = new LinkedHashMap<>();
        buckets.put("olderThan6y",
                countClosedBefore(now.minus(java.time.Duration.ofDays(6L * 365))));
        buckets.put("olderThan7y",
                countClosedBefore(now.minus(java.time.Duration.ofDays(7L * 365))));
        buckets.put("olderThan10y",
                countClosedBefore(now.minus(java.time.Duration.ofDays(10L * 365))));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("policyClosedYears", retentionClosedYears);
        report.put("buckets", buckets);
        return report;
    }

    private Long countClosedBefore(Instant cutoff) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM claim WHERE status = 'CLOSED' "
                        + "AND closed_at IS NOT NULL AND closed_at < ?",
                Long.class, java.sql.Timestamp.from(cutoff));
        return count == null ? 0 : count;
    }

    private static boolean isRedactedHolder(Map<String, Object> policy) {
        Object name = policy.get("holder_name");
        Object email = policy.get("holder_email");
        return "REDACTED".equals(name == null ? null : String.valueOf(name))
                || (email != null && String.valueOf(email)
                        .startsWith("redacted+")
                        && String.valueOf(email).endsWith("@example.invalid"));
    }
}