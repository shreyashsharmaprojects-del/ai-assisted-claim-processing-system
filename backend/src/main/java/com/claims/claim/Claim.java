package com.claims.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A claim as filed at FNOL. Sliced vertically: columns the current slice writes are mapped
 * (slice 1: FNOL data; slice 2: assignment); reserve/decision fields arrive with theirs.
 */
@Entity
@Table(name = "claim")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_number", nullable = false, unique = true)
    private String claimNumber;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "claimant_sub", nullable = false)
    private String claimantSub;

    @Column(name = "level", nullable = false)
    private String level;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "loss_date", nullable = false)
    private LocalDate lossDate;

    @Column(name = "loss_location", nullable = false)
    private String lossLocation;

    @Column(name = "loss_description", nullable = false)
    private String lossDescription;

    @Column(name = "claimant_remarks")
    private String claimantRemarks;

    @Column(name = "assigned_adjuster_id")
    private Long assignedAdjusterId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    /** The adjuster's internal reserve estimate; never claimant-visible (slice 3). */
    @Column(name = "reserve_amount")
    private BigDecimal reserveAmount;

    /** APPROVED | DENIED; null until the claim is decided (slice 4). */
    @Column(name = "decision")
    private String decision;

    /** Claimant-visible remarks (denials carry the rationale as remarks); never internal notes. */
    @Column(name = "decision_remarks")
    private String decisionRemarks;

    /** The indemnity figure; set once, at closure, by an approval. */
    @Column(name = "indemnity_amount")
    private BigDecimal indemnityAmount;

    /**
     * V2-2: server-computed sum of the filed cover amounts (never client-trusted).
     * NULL on pre-V2-2 rows (single-figure V1 claims predate cover splits).
     */
    @Column(name = "claimed_total")
    private BigDecimal claimedTotal;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Claim() {
        // for JPA
    }

    public Claim(String claimNumber, Long policyId, String claimantSub, String level, String status,
            LocalDate lossDate, String lossLocation, String lossDescription, String claimantRemarks) {
        this.claimNumber = claimNumber;
        this.policyId = policyId;
        this.claimantSub = claimantSub;
        this.level = level;
        this.status = status;
        this.lossDate = lossDate;
        this.lossLocation = lossLocation;
        this.lossDescription = lossDescription;
        this.claimantRemarks = claimantRemarks;
    }

    public Long getId() {
        return id;
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public Long getPolicyId() {
        return policyId;
    }

    public String getClaimantSub() {
        return claimantSub;
    }

    public String getLevel() {
        return level;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getLossDate() {
        return lossDate;
    }

    public String getLossLocation() {
        return lossLocation;
    }

    public String getLossDescription() {
        return lossDescription;
    }

    public String getClaimantRemarks() {
        return claimantRemarks;
    }

    public Long getAssignedAdjusterId() {
        return assignedAdjusterId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public BigDecimal getReserveAmount() {
        return reserveAmount;
    }

    public void setReserveAmount(BigDecimal reserveAmount) {
        this.reserveAmount = reserveAmount;
    }

    public String getDecision() {
        return decision;
    }

    public String getDecisionRemarks() {
        return decisionRemarks;
    }

    public BigDecimal getIndemnityAmount() {
        return indemnityAmount;
    }

    public BigDecimal getClaimedTotal() {
        return claimedTotal;
    }

    public void setClaimedTotal(BigDecimal claimedTotal) {
        this.claimedTotal = claimedTotal;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    /**
     * Approves an indemnity figure and closes the claim (slice 4). Call inside the decision
     * transaction; the matching payment row is recorded by the caller.
     */
    public void approve(BigDecimal indemnityAmount, Instant at) {
        this.decision = "APPROVED";
        this.indemnityAmount = indemnityAmount;
        this.status = "CLOSED";
        this.closedAt = at;
    }

    /** Denies the claim and closes it; the remarks are the claimant-visible rationale. */
    public void deny(String remarks, Instant at) {
        this.decision = "DENIED";
        this.decisionRemarks = remarks;
        this.status = "CLOSED";
        this.closedAt = at;
    }

    /**
     * Moves the claim to the supervisor escalation state (no adjuster holds it). Used when a
     * decision amount exceeds the L2 authority limit, or when no L2 adjuster is provisioned
     * to take an escalation.
     */
    public void escalateToSupervisor() {
        this.status = "ESCALATED_SUPERVISOR";
        this.assignedAdjusterId = null;
        this.assignedAt = null;
    }

    /** Escalation re-routes the claim at the higher level before it is re-assigned. */
    public void setLevel(String level) {
        this.level = level;
    }

    /**
     * Assigns this claim to an adjuster: records the assignee and moves the status to
     * UNDER_REVIEW. Call inside the creating transaction so the row is never observable
     * as UNASSIGNED.
     */
    public void assignTo(Long adjusterId, Instant at) {
        this.assignedAdjusterId = adjusterId;
        this.assignedAt = at;
        this.status = "UNDER_REVIEW";
    }
}
