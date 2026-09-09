package com.claims.claim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.api.ClaimNotFoundException;
import com.claims.api.InvalidRequestException;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.policy.Policy;
import com.claims.policy.PolicyRepository;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * S3: the required-documents checklist. Seeds PENDING rows at FNOL, serves the
 * full rows to the assignee/supervisor and the walled {displayName, status}
 * shape to the owning claimant, applies assignee link/waive transitions
 * (audited), and auto-links uploads that carry a matching {@code docKey}.
 *
 * <p>Object auth is 404-not-403 throughout (via {@link ClaimAccess} for staff
 * and a holder check for claimants); role gates stay in SecurityConfig.
 */
@Service
public class RequiredDocumentService {

    /** Internal full row: every checklist field, including who decided. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequiredDocumentView(Long checkId, String docKey, String displayName,
            String status, Long attachmentId, String decidedBy, Instant decidedAt) {
    }

    /** Internal GET wrapper: counts + full rows. */
    public record InternalDocsView(int documentsReceived, int documentsTotal,
            List<RequiredDocumentView> items) {
    }

    /** Claimant GET wrapper: counts + per-item labels, never internals. */
    public record ClaimantDocsView(int documentsReceived, int documentsTotal,
            List<ClaimantClaimView.DocItem> items) {
    }

    /** Counts + claimant items for embedding in the claimant tracker DTO. */
    public record ClaimantDocs(int received, int total,
            List<ClaimantClaimView.DocItem> items) {
    }

    public record LinkRequest(Long attachmentId) {
    }

    public record WaiveRequest(String rationale) {
    }

    private final ClaimRepository claims;
    private final PolicyRepository policies;
    private final RequiredDocumentRepository requiredDocs;
    private final ClaimDocumentCheckRepository checks;
    private final AttachmentRepository attachments;
    private final ClaimAccess access;
    private final AuditLogWriter auditLog;

    public RequiredDocumentService(ClaimRepository claims, PolicyRepository policies,
            RequiredDocumentRepository requiredDocs, ClaimDocumentCheckRepository checks,
            AttachmentRepository attachments, ClaimAccess access, AuditLogWriter auditLog) {
        this.claims = claims;
        this.policies = policies;
        this.requiredDocs = requiredDocs;
        this.checks = checks;
        this.attachments = attachments;
        this.access = access;
        this.auditLog = auditLog;
    }

    /**
     * FNOL hook (in the filing transaction): one PENDING row per required doc
     * of the policy's product. Products without seeds (e.g. HLTH-ORPHAN) get an
     * empty checklist.
     */
    @Transactional
    public void seedForClaim(Long claimId, String productCode) {
        for (RequiredDocument doc : requiredDocs
                .findByProductCodeOrderBySortOrderAscIdAsc(productCode)) {
            checks.save(new ClaimDocumentCheck(claimId, doc.getId()));
        }
    }

    /**
     * Fails fast on an unknown doc key (400 naming the valid keys) — called
     * before any side effect on the upload paths.
     */
    public void validateDocKey(String productCode, String rawDocKey) {
        if (rawDocKey == null || rawDocKey.isBlank()) {
            return;
        }
        String key = rawDocKey.trim().toUpperCase(Locale.ROOT);
        requiredDocs.findByProductCodeAndDocKey(productCode, key).orElseThrow(
                () -> unknownKeyException(rawDocKey.trim(), productCode));
    }

    public List<String> validKeys(String productCode) {
        return requiredDocs.findByProductCodeOrderBySortOrderAscIdAsc(productCode)
                .stream().map(RequiredDocument::getDocKey).toList();
    }

    /**
     * Upload hook: links the new attachment to the matching PENDING check
     * (RECEIVED, audited). Blank keys are a no-op; already-decided checks keep
     * their outcome (first evidence wins); unknown keys are a 400.
     */
    @Transactional
    public void tryAutoLink(Claim claim, String rawDocKey, Long attachmentId,
            String actorSub) {
        if (rawDocKey == null || rawDocKey.isBlank()) {
            return;
        }
        Policy policy = policies.findById(claim.getPolicyId()).orElseThrow(
                () -> new IllegalStateException("Claim " + claim.getClaimNumber()
                        + " references a missing policy " + claim.getPolicyId()));
        String key = rawDocKey.trim().toUpperCase(Locale.ROOT);
        RequiredDocument doc = requiredDocs
                .findByProductCodeAndDocKey(policy.getProductCode(), key)
                .orElseThrow(() -> unknownKeyException(rawDocKey.trim(),
                        policy.getProductCode()));
        ClaimDocumentCheck check = checks
                .findByClaimIdAndRequiredDocumentId(claim.getId(), doc.getId())
                .orElse(null);
        if (check == null || !"PENDING".equals(check.getStatus())) {
            return;
        }
        check.setStatus("RECEIVED");
        check.setAttachmentId(attachmentId);
        check.setDecidedBy(actorSub);
        check.setDecidedAt(Instant.now());
        checks.save(check);
        auditLog.append(actorSub, "DOC_LINKED", "CLAIM", claim.getId(), null,
                AuditJson.of(Map.of("claimNumber", claim.getClaimNumber(),
                        "docKey", doc.getDocKey(), "checkId", check.getId(),
                        "attachmentId", attachmentId, "status", "RECEIVED",
                        "autoLinked", true)),
                null);
    }

    /**
     * GET: full rows for the assignee/supervisor, the walled shape for the
     * owning claimant, 404 for everyone else (never revealing the number).
     */
    public Object listFor(String claimNumber, String subject, boolean supervisor) {
        Claim claim = claims.findByClaimNumber(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (access.internalReaderMaySee(claim, subject, supervisor)) {
            List<RequiredDocumentView> rows = orderedRows(claim.getId()).stream()
                    .map(row -> viewOf(row.check(), row.doc())).toList();
            int[] counts = countsOf(rows.stream().map(RequiredDocumentView::status).toList());
            return new InternalDocsView(counts[0], counts[1], rows);
        }
        if (claim.getClaimantSub().equals(subject)) {
            ClaimantDocs docs = claimantDocs(claim.getId());
            return new ClaimantDocsView(docs.received(), docs.total(), docs.items());
        }
        throw new ClaimNotFoundException();
    }

    /** Counts + claimant items for the tracker DTOs (never internals). */
    public ClaimantDocs claimantDocs(Long claimId) {
        List<CheckRow> rows = orderedRows(claimId);
        List<ClaimantClaimView.DocItem> items = new ArrayList<>();
        int received = 0;
        for (CheckRow row : rows) {
            if ("RECEIVED".equals(row.check().getStatus())) {
                received++;
            }
            items.add(new ClaimantClaimView.DocItem(row.doc().getDisplayName(),
                    row.check().getStatus()));
        }
        return new ClaimantDocs(received, rows.size(), items);
    }

    /** Counts for the internal views (received, total). */
    public int[] counts(Long claimId) {
        List<ClaimDocumentCheck> rows = checks.findByClaimIdOrderByIdAsc(claimId);
        int received = 0;
        for (ClaimDocumentCheck row : rows) {
            if ("RECEIVED".equals(row.getStatus())) {
                received++;
            }
        }
        return new int[] {received, rows.size()};
    }

    /** Assignee-only (404 otherwise): link an attachment as the doc evidence. */
    @Transactional
    public RequiredDocumentView link(String claimNumber, Long checkId, Long attachmentId,
            String actorSub, boolean supervisor) {
        Claim claim = requireVisibleForUpdate(claimNumber, actorSub, supervisor);
        if (attachmentId == null) {
            throw new InvalidRequestException("An attachment is required to link a document.");
        }
        CheckRow row = checkOf(claim, checkId);
        Attachment attachment = attachments.findById(attachmentId)
                .orElseThrow(ClaimNotFoundException::new);
        if (!attachment.getClaimId().equals(claim.getId())) {
            throw new ClaimNotFoundException();
        }
        ClaimDocumentCheck check = row.check();
        check.setStatus("RECEIVED");
        check.setAttachmentId(attachment.getId());
        check.setDecidedBy(actorSub);
        check.setDecidedAt(Instant.now());
        checks.save(check);
        auditLog.append(actorSub, "DOC_LINKED", "CLAIM", claim.getId(), null,
                AuditJson.of(Map.of("claimNumber", claimNumber,
                        "docKey", row.doc().getDocKey(), "checkId", check.getId(),
                        "attachmentId", attachment.getId(), "status", "RECEIVED")),
                null);
        return viewOf(check, row.doc());
    }

    /** Assignee-only (404 otherwise): waive a doc with a rationale. */
    @Transactional
    public RequiredDocumentView waive(String claimNumber, Long checkId, String rationale,
            String actorSub, boolean supervisor) {
        Claim claim = requireVisibleForUpdate(claimNumber, actorSub, supervisor);
        if (rationale == null || rationale.isBlank()) {
            throw new InvalidRequestException(
                    "A rationale is required to waive a required document.");
        }
        CheckRow row = checkOf(claim, checkId);
        ClaimDocumentCheck check = row.check();
        check.setStatus("WAIVED");
        check.setAttachmentId(null);
        check.setDecidedBy(actorSub);
        check.setDecidedAt(Instant.now());
        checks.save(check);
        auditLog.append(actorSub, "DOC_WAIVED", "CLAIM", claim.getId(), null,
                AuditJson.of(Map.of("claimNumber", claimNumber,
                        "docKey", row.doc().getDocKey(), "checkId", check.getId(),
                        "status", "WAIVED")),
                rationale.trim());
        return viewOf(check, row.doc());
    }

    private Claim requireVisibleForUpdate(String claimNumber, String actorSub,
            boolean supervisor) {
        Claim claim = claims.findByClaimNumberForUpdate(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!access.internalReaderMaySee(claim, actorSub, supervisor)) {
            throw new ClaimNotFoundException();
        }
        return claim;
    }

    private CheckRow checkOf(Claim claim, Long checkId) {
        ClaimDocumentCheck check = checks.findByIdAndClaimId(checkId, claim.getId())
                .orElseThrow(ClaimNotFoundException::new);
        RequiredDocument doc = requiredDocs.findById(check.getRequiredDocumentId())
                .orElseThrow(ClaimNotFoundException::new);
        return new CheckRow(check, doc);
    }

    private record CheckRow(ClaimDocumentCheck check, RequiredDocument doc) {
    }

    private List<CheckRow> orderedRows(Long claimId) {
        List<ClaimDocumentCheck> rows = checks.findByClaimIdOrderByIdAsc(claimId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, RequiredDocument> byId = new HashMap<>();
        for (RequiredDocument doc : requiredDocs.findAllById(rows.stream()
                .map(ClaimDocumentCheck::getRequiredDocumentId).toList())) {
            byId.put(doc.getId(), doc);
        }
        List<CheckRow> joined = new ArrayList<>();
        for (ClaimDocumentCheck row : rows) {
            RequiredDocument doc = byId.get(row.getRequiredDocumentId());
            if (doc != null) {
                joined.add(new CheckRow(row, doc));
            }
        }
        joined.sort(Comparator.comparingInt((CheckRow row) -> row.doc().getSortOrder())
                .thenComparingLong(row -> row.doc().getId()));
        return joined;
    }

    private static RequiredDocumentView viewOf(ClaimDocumentCheck check,
            RequiredDocument doc) {
        return new RequiredDocumentView(check.getId(), doc.getDocKey(),
                doc.getDisplayName(), check.getStatus(), check.getAttachmentId(),
                check.getDecidedBy(), check.getDecidedAt());
    }

    private static int[] countsOf(List<String> statuses) {
        int received = 0;
        for (String status : statuses) {
            if ("RECEIVED".equals(status)) {
                received++;
            }
        }
        return new int[] {received, statuses.size()};
    }

    private InvalidRequestException unknownKeyException(String rawKey, String productCode) {
        List<String> valid = validKeys(productCode);
        String suffix = valid.isEmpty() ? "this product requires no documents."
                : "Valid documents: " + String.join(", ", valid) + ".";
        return new InvalidRequestException(
                "Unknown document '" + rawKey + "'. " + suffix);
    }
}
