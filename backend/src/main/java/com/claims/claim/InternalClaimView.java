package com.claims.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The full internal view of a claim (assigned adjuster / supervisor only). Carries
 * policy + coverage, the reserve, and the internal notes — none of which may ever appear
 * on a claimant-facing surface (the visibility wall is enforced at the API layer: this
 * record is simply never returned toward a claimant).
 */
public record InternalClaimView(String claimNumber, String status, String level,
        String policyNumber, String productCode, Object coverage, String holderName,
        LocalDate lossDate, String lossLocation, String lossDescription, String claimantRemarks,
        BigDecimal reserveAmount, Instant createdAt, String assignedTo,
        List<AttachmentView> attachments, List<NoteView> notes) {

    public record AttachmentView(Long id, String originalName, String contentType) {
    }

    public record NoteView(Long id, String body, String author, Instant createdAt) {
    }
}
