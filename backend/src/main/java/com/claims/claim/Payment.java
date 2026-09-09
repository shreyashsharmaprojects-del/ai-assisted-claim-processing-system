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
 * The recorded payment for a closed claim (slice 4). V3 S6: one row per closure —
 * unique {@code (claim_id, seq)} — and {@code amount} always equals the claim's
 * {@code indemnity_amount} at that closure — a recorded fact, not a money movement
 * (see docs/plan.md).
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    /** V22 (V3 S6): closure ordinal per claim — 1 for the first closure, max+1 after. */
    @Column(name = "seq", nullable = false)
    private int seq = 1;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "authorized_by_id")
    private Long authorizedById;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    protected Payment() {
        // for JPA
    }

    public Payment(Long claimId, BigDecimal amount, Long authorizedById, Instant authorizedAt) {
        this(claimId, 1, amount, authorizedById, authorizedAt);
    }

    /** V22 (V3 S6): re-closures pass seq = max+1 for the claim. */
    public Payment(Long claimId, int seq, BigDecimal amount, Long authorizedById,
            Instant authorizedAt) {
        this.claimId = claimId;
        this.seq = seq;
        this.amount = amount;
        this.authorizedById = authorizedById;
        this.authorizedAt = authorizedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Long getAuthorizedById() {
        return authorizedById;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }
}
