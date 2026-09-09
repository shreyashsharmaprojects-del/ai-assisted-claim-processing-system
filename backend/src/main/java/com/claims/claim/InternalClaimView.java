package com.claims.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The full internal view of a claim (assigned adjuster / supervisor only). Carries
 * policy + coverage, the reserve, and the internal notes — none of which may ever appear
 * on a claimant-facing surface (the visibility wall is enforced at the API layer: this
 * record is simply never returned toward a claimant). Every field is consumed by the
 * adjuster claim screen (see docs/decisions.md — no dead DTO surface).
 */
public record InternalClaimView(String claimNumber, String status, String level,
        String policyNumber, String productCode, Object coverage, String holderName,
        LocalDate lossDate, String lossLocation, String lossDescription, String claimantRemarks,
        BigDecimal reserveAmount, String assignedTo, List<AttachmentView> attachments,
        List<NoteView> notes, Integer documentsReceived, Integer documentsTotal) {

    public record AttachmentView(Long id, String originalName, String label,
            Long verificationId, String docType, Long replacesId) {

        /** Pre-V16 convenience: rows without a label. */
        public AttachmentView(Long id, String originalName) {
            this(id, originalName, null, null, null, null);
        }

        /** Pre-V17 convenience: rows without a verification link. */
        public AttachmentView(Long id, String originalName, String label) {
            this(id, originalName, label, null, null, null);
        }

        /** Pre-V20 convenience: rows without supersede metadata. */
        public AttachmentView(Long id, String originalName, String label,
                Long verificationId) {
            this(id, originalName, label, verificationId, null, null);
        }
    }

    public record NoteView(Long id, String body, String author) {
    }
}
