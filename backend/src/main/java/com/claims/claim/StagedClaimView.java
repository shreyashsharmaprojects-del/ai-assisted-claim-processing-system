package com.claims.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * V2-4/V2-5/V2-6: the staged full view backing the claim workspace. Extends the V1
 * {@link InternalClaimView} with the orthogonal workflow position (stage), the
 * NEED_INFO round-trip state, per-cover money + outcomes + proposals, the verification
 * history, and the actor's authority context for this product. Internal-only — never
 * returned toward a claimant.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StagedClaimView(String claimNumber, String status, String stage, String level,
        String policyNumber, String productCode, Object coverage, String holderName,
        java.time.LocalDate lossDate, String lossLocation, String lossDescription,
        String claimantRemarks, BigDecimal reserveAmount, String assignedTo,
        String needInfoReason, String needInfoPriorStage,
        List<StagedCoverView> covers, BigDecimal claimedTotal,
        List<VerificationView> verifications, BigDecimal authorityLimit,
        String authorityBasis, Boolean proposalsSaved, BigDecimal proposedTotal) {

    public record StagedCoverView(String coverCode, String displayName,
            BigDecimal claimedAmount, BigDecimal subLimit, boolean aboveLimit,
            BigDecimal assessedAmount, BigDecimal approvedAmount, BigDecimal deductibleAmount,
            BigDecimal adjustmentAmount, BigDecimal netPayable, String decision,
            String decisionRemarks, boolean proposal) {
    }

    public record VerificationView(Long id, String type, String status, String outcome,
            String notes, String evidenceRefs, String performedBy, Instant startedAt,
            Instant completedAt) {
    }
}
