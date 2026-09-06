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
 * The recorded payment for a closed claim (slice 4). Exactly one payment per claim
 * (unique {@code claim_id}), and {@code amount} always equals the claim's
 * {@code indemnity_amount} — a recorded fact, not a money movement (see docs/plan.md).
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, unique = true)
    private Long claimId;

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
        this.claimId = claimId;
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
