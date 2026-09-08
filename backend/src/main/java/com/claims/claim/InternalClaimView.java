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
        List<NoteView> notes) {

    public record AttachmentView(Long id, String originalName, String label) {

        /** Pre-V16 convenience: rows without a label. */
        public AttachmentView(Long id, String originalName) {
            this(id, originalName, null);
        }
    }

    public record NoteView(Long id, String body, String author) {
    }
}
