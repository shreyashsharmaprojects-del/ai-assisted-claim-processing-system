package com.claims.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.api.ClaimNotFoundException;
import com.claims.api.InvalidRequestException;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.policy.Policy;
import com.claims.policy.PolicyRepository;
import com.claims.staff.AppUser;
import com.claims.staff.AppUserRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Slice 3: the assigned adjuster's (and supervisor's) work on a claim — full internal
 * view (policy + coverage + reserve + notes + photos), reserve updates, internal notes,
 * and photo downloads. Every read/write goes through {@link ClaimAccess}: anything the
 * caller may not see is a 404.
 *
 * <p>The visibility wall is structural: the only shapes this service returns are the
 * internal view/note/download records, which are never routed to claimant surfaces.
 */
@Service
public class ClaimWorkService {

    private static final Logger log = LoggerFactory.getLogger(ClaimWorkService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The claim.reserve_amount column is NUMERIC(14,2): nothing may exceed or out-scale it. */
    private static final BigDecimal MAX_RESERVE = new BigDecimal("999999999999.99");

    private final ClaimRepository claims;
    private final PolicyRepository policies;
    private final AttachmentRepository attachments;
    private final InternalNoteRepository notes;
    private final VerificationRepository verifications;
    private final AppUserRepository appUsers;
    private final ClaimAccess access;
    private final AuditLogWriter auditLog;
    private final JdbcTemplate jdbcTemplate;
    private final PhotoStorage photoStorage;
    private final RequiredDocumentService requiredDocuments;

    public ClaimWorkService(ClaimRepository claims, PolicyRepository policies,
            AttachmentRepository attachments, InternalNoteRepository notes,
            VerificationRepository verifications, AppUserRepository appUsers,
            ClaimAccess access, AuditLogWriter auditLog,
            JdbcTemplate jdbcTemplate, PhotoStorage photoStorage,
            RequiredDocumentService requiredDocuments) {
        this.claims = claims;
        this.policies = policies;
        this.attachments = attachments;
        this.notes = notes;
        this.verifications = verifications;
        this.appUsers = appUsers;
        this.access = access;
        this.auditLog = auditLog;
        this.jdbcTemplate = jdbcTemplate;
        this.photoStorage = photoStorage;
        this.requiredDocuments = requiredDocuments;
    }

    public InternalClaimView fullView(String claimNumber, String actorSub, boolean supervisor) {
        return fullView(requireVisible(claimNumber, actorSub, supervisor));
    }

    /** Sets/updates the reserve (never authority-gated — an internal estimate). */
    @Transactional
    public InternalClaimView updateReserve(String claimNumber, String actorSub,
            boolean supervisor, BigDecimal amount) {
        Claim claim = requireVisible(claimNumber, actorSub, supervisor);
        if (amount == null || amount.signum() < 0) {
            throw new InvalidRequestException("Reserve amount must be zero or more.");
        }
        if (amount.scale() > 2) {
            // Reject rather than let the DB silently round: the response echoes the
            // in-memory value, so rounding here would diverge response from storage.
            throw new InvalidRequestException("Reserve amount may have at most 2 decimal places.");
        }
        if (amount.compareTo(MAX_RESERVE) > 0) {
            throw new InvalidRequestException("Reserve amount is too large (maximum 999999999999.99).");
        }
        BigDecimal before = claim.getReserveAmount();
        claim.setReserveAmount(amount);
        claims.save(claim);
        auditLog.append(actorSub, "RESERVE_SET", "CLAIM", claim.getId(),
                AuditJson.of(before == null ? Map.of() : Map.of("reserveAmount", before)),
                AuditJson.of(Map.of("claimNumber", claimNumber, "reserveAmount", amount)),
                null);
        return fullView(claim);
    }

    /** Adds an internal note by the acting staff user. */
    @Transactional
    public InternalClaimView.NoteView addNote(String claimNumber, String actorSub,
            boolean supervisor, String body) {
        Claim claim = requireVisible(claimNumber, actorSub, supervisor);
        if (body == null || body.isBlank()) {
            throw new InvalidRequestException("Note text is required.");
        }
        Long authorId = appUsers.findByKeycloakSub(actorSub).map(AppUser::getId).orElse(null);
        InternalNote note = notes.save(new InternalNote(claim.getId(), authorId, actorSub,
                body.trim(), Instant.now()));
        return new InternalClaimView.NoteView(note.getId(), note.getBody(),
                displayNameOf(authorId));
    }

    /**
     * V16: attaches a document to an open claim — the adjuster's per-step upload
     * (each workflow stage carries its own attach form) and the claimant's
     * NEED_INFO response upload share this path. The label is the dropdown choice
     * or the claimant/adjuster free-text name; blank labels are stored NULL and
     * the row lists by upload order. Closed claims take no new documents.
     *
     * <p>V17: stamps the uploader's subject (timeline attribution) and accepts
     * an optional verification link (per-check evidence). Unknown verification
     * ids and cross-claim links are a 404, never a leak.
     */
    @Transactional
    public InternalClaimView.AttachmentView attach(String claimNumber, String actorSub,
            boolean supervisor, org.springframework.web.multipart.MultipartFile file,
            String label) {
        return attach(claimNumber, actorSub, supervisor, file, label, null);
    }

    @Transactional
    public InternalClaimView.AttachmentView attach(String claimNumber, String actorSub,
            boolean supervisor, org.springframework.web.multipart.MultipartFile file,
            String label, Long verificationId) {
        return attach(claimNumber, actorSub, supervisor, file, label, verificationId, null);
    }

    /**
     * S3: optional {@code docKey} auto-links the upload to the matching PENDING
     * required-doc check (RECEIVED + attachment id, audited). The key is
     * validated before any side effect: unknown keys are a 400 naming the
     * valid keys.
     *
     * <p>V20 (S4): optional advisory {@code docType} (≤60 chars, else 400) and
     * {@code replacesId} (the prior attachment this upload supersedes; the
     * replaced row must belong to the same claim, else 404). Independent of
     * the docKey auto-link, which keeps working as before.
     */
    @Transactional
    public InternalClaimView.AttachmentView attach(String claimNumber, String actorSub,
            boolean supervisor, org.springframework.web.multipart.MultipartFile file,
            String label, Long verificationId, String docKey) {
        return attach(claimNumber, actorSub, supervisor, file, label, verificationId,
                docKey, null, null);
    }

    @Transactional
    public InternalClaimView.AttachmentView attach(String claimNumber, String actorSub,
            boolean supervisor, org.springframework.web.multipart.MultipartFile file,
            String label, Long verificationId, String docKey, String docType,
            Long replacesId) {
        Claim claim = requireVisible(claimNumber, actorSub, supervisor);
        if ("CLOSED".equals(claim.getStatus()) || claim.getDecision() != null) {
            throw new InvalidRequestException("This claim has already been decided.");
        }
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("A file is required.");
        }
        String trimmedDocType = docType == null || docType.isBlank() ? null : docType.trim();
        if (trimmedDocType != null && trimmedDocType.length() > 60) {
            throw new InvalidRequestException(
                    "The document type may be at most 60 characters.");
        }
        if (replacesId != null) {
            attachments.findById(replacesId)
                    .filter(a -> a.getClaimId().equals(claim.getId()))
                    .orElseThrow(ClaimNotFoundException::new);
        }
        if (docKey != null && !docKey.isBlank()) {
            Policy policy = policies.findById(claim.getPolicyId())
                    .orElseThrow(() -> new IllegalStateException("Claim " + claimNumber
                            + " references a missing policy " + claim.getPolicyId()));
            requiredDocuments.validateDocKey(policy.getProductCode(), docKey);
        }
        if (verificationId != null) {
            verifications.findByIdAndClaimId(verificationId, claim.getId())
                    .orElseThrow(ClaimNotFoundException::new);
        }
        List<StoredPhoto> stored = photoStorage.store(List.of(file), claim.getId());
        StoredPhoto photo = stored.get(0);
        String trimmed = label == null || label.isBlank() ? null : label.trim();
        if (trimmed != null && trimmed.length() > 120) {
            throw new InvalidRequestException(
                    "The document name may be at most 120 characters.");
        }
        Attachment row = new Attachment(claim.getId(),
                photo.storagePath(), photo.contentType(), photo.originalName(), trimmed,
                actorSub, verificationId, photo.sha256(), photo.sizeBytes());
        row.setDocType(trimmedDocType);
        row.setReplacesAttachmentId(replacesId);
        row = attachments.save(row);
        requiredDocuments.tryAutoLink(claim, docKey, row.getId(), actorSub);
        return new InternalClaimView.AttachmentView(row.getId(), row.getOriginalName(),
                row.getLabel(), row.getVerificationId(), row.getDocType(),
                row.getReplacesAttachmentId());
    }

    /**
     * The attachment binary for a visible claim. S1 integrity: when the row
     * carries a sha pin, the bytes are re-hashed and a mismatch is an error
     * log + 404 (never serve corrupt bytes; 404-not-403 keeps the rule).
     * Legacy NULL rows (pre-V18) skip verification and serve as before.
     *
     * <p>S2: served via {@code photoStorage.load()} so both backends (filesystem
     * and S3) serve identically — keys ({@code {claimId}/{uuid}{ext}}) are
     * backend-portable; legacy absolute-path rows resolve on the filesystem
     * backend only.
     */
    public AttachmentDownload download(String claimNumber, String actorSub,
            boolean supervisor, Long attachmentId) {
        Claim claim = requireVisible(claimNumber, actorSub, supervisor);
        Attachment attachment = attachments.findById(attachmentId)
                .filter(a -> a.getClaimId().equals(claim.getId()))
                .orElseThrow(ClaimNotFoundException::new);
        byte[] bytes;
        try {
            bytes = photoStorage.load(attachment.getStoragePath());
        } catch (IllegalStateException ex) {
            log.error("Attachment {} for claim {} points at a missing file: {}",
                    attachment.getId(), claimNumber, attachment.getStoragePath(), ex);
            throw new ClaimNotFoundException();
        }
        if (attachment.getSha256() != null
                && !attachment.getSha256().equalsIgnoreCase(
                        FilesystemPhotoStorage.sha256Hex(bytes))) {
            log.error("Attachment {} for claim {} failed integrity check (sha mismatch)",
                    attachment.getId(), claimNumber);
            throw new ClaimNotFoundException();
        }
        return new AttachmentDownload(bytes,
                attachment.getContentType(), attachment.getOriginalName());
    }

    private Claim requireVisible(String claimNumber, String actorSub, boolean supervisor) {
        Claim claim = claims.findByClaimNumber(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!access.internalReaderMaySee(claim, actorSub, supervisor)) {
            throw new ClaimNotFoundException();
        }
        return claim;
    }

    private InternalClaimView fullView(Claim claim) {
        Policy policy = policies.findById(claim.getPolicyId())
                .orElseThrow(() -> new IllegalStateException(
                        "Claim " + claim.getClaimNumber() + " references a missing policy "
                                + claim.getPolicyId()));
        List<InternalClaimView.AttachmentView> attachmentViews = attachments
                .findByClaimIdOrderById(claim.getId()).stream()
                .map(a -> new InternalClaimView.AttachmentView(a.getId(),
                        a.getOriginalName(), a.getLabel(), a.getVerificationId(),
                        a.getDocType(), a.getReplacesAttachmentId()))
                .toList();
        List<InternalClaimView.NoteView> noteViews = notes
                .findByClaimIdOrderByCreatedAtAscIdAsc(claim.getId()).stream()
                .map(n -> new InternalClaimView.NoteView(n.getId(), n.getBody(),
                        displayNameOf(n.getAuthorId())))
                .toList();
        int[] docCounts = requiredDocuments.counts(claim.getId());
        return new InternalClaimView(
                claim.getClaimNumber(),
                claim.getStatus(),
                claim.getLevel(),
                policy.getPolicyNumber(),
                policy.getProductCode(),
                coverageOf(policy.getId()),
                policy.getHolderName(),
                claim.getLossDate(),
                claim.getLossLocation(),
                claim.getLossDescription(),
                claim.getClaimantRemarks(),
                claim.getReserveAmount(),
                displayNameOf(claim.getAssignedAdjusterId()),
                attachmentViews,
                noteViews,
                docCounts[0], docCounts[1]);
    }

    private String displayNameOf(Long appUserId) {
        if (appUserId == null) {
            return null;
        }
        return appUsers.findById(appUserId).map(AppUser::getDisplayName).orElse(null);
    }

    /** The policy coverage is JSONB and deliberately unmapped on the entity; read as JSON. */
    private Object coverageOf(Long policyId) {
        String text = jdbcTemplate.queryForObject(
                "SELECT coverage::text FROM policy WHERE id = ?", String.class, policyId);
        try {
            return text == null ? null : JSON.readTree(text);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Policy " + policyId + " holds invalid coverage JSON", ex);
        }
    }
}
