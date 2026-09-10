package com.claims.claim;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * V2-2: one filed cover on a claim. FILED state is {@code claimed_amount} with
 * decision {@code PENDING}; {@code assessed/approved/net_payable} arrive with V2-5
 * and stay NULL until then. Above-sub-limit filings are legal rows (locked rule 1):
 * no filing-time check lives here.
 */
@Entity
@Table(name = "claim_cover")
public class ClaimCover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "cover_code", nullable = false)
    private String coverCode;

    @Column(name = "claimed_amount", nullable = false)
    private BigDecimal claimedAmount;

    @Column(name = "assessed_amount")
    private BigDecimal assessedAmount;

    @Column(name = "approved_amount")
    private BigDecimal approvedAmount;

    @Column(name = "deductible_amount", nullable = false)
    private BigDecimal deductibleAmount = BigDecimal.ZERO;

    @Column(name = "adjustment_amount", nullable = false)
    private BigDecimal adjustmentAmount = BigDecimal.ZERO;

    @Column(name = "net_payable")
    private BigDecimal netPayable;

    @Column(name = "decision", nullable = false)
    private String decision = "PENDING";

    @Column(name = "decision_remarks")
    private String decisionRemarks;

    /** V23 (V3 S8): structured denial code on a REJECTED cover; internal-only. */
    @Column(name = "denial_reason")
    private String denialReason;

    @Column(name = "is_proposal", nullable = false)
    private boolean proposal = false;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected ClaimCover() {
        // for JPA
    }

    public ClaimCover(Long claimId, String coverCode, BigDecimal claimedAmount) {
        this.claimId = claimId;
        this.coverCode = coverCode;
        this.claimedAmount = claimedAmount;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public String getCoverCode() {
        return coverCode;
    }

    public BigDecimal getClaimedAmount() {
        return claimedAmount;
    }

    public BigDecimal getAssessedAmount() {
        return assessedAmount;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public BigDecimal getDeductibleAmount() {
        return deductibleAmount;
    }

    public BigDecimal getAdjustmentAmount() {
        return adjustmentAmount;
    }

    public BigDecimal getNetPayable() {
        return netPayable;
    }

    public String getDecision() {
        return decision;
    }

    public String getDecisionRemarks() {
        return decisionRemarks;
    }

    public String getDenialReason() {
        return denialReason;
    }

    public void setDenialReason(String denialReason) {
        this.denialReason = denialReason;
    }

    public boolean isProposal() {
        return proposal;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    /** V2-5: assessment sets the within-limits figure the approval then binds. */
    public void setAssessedAmount(BigDecimal assessedAmount) {
        this.assessedAmount = assessedAmount;
    }

    /** V2-5/V2-6: applied figures on closure, or proposed figures while gated. */
    public void applyDecision(String decision, BigDecimal approvedAmount,
            BigDecimal deductibleAmount, BigDecimal adjustmentAmount, BigDecimal netPayable,
            String remarks, Long decidedBy, Instant decidedAt, boolean proposal) {
        this.decision = decision;
        this.approvedAmount = approvedAmount;
        this.deductibleAmount = deductibleAmount;
        this.adjustmentAmount = adjustmentAmount;
        this.netPayable = netPayable;
        this.decisionRemarks = remarks;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.proposal = proposal;
    }
}
