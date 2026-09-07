package com.claims.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One filed FNOL, recorded in the filing transaction. The rate limiter counts recent
 * rows per claimant subject; ops can trace which subject filed from which address.
 * Rows are written once and never updated.
 */
@Entity
@Table(name = "fnol_submission")
public class FnolSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claimant_sub", nullable = false)
    private String claimantSub;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FnolSubmission() {
        // for JPA
    }

    public FnolSubmission(String claimantSub, Long claimId, String ipAddress, Instant createdAt) {
        this.claimantSub = claimantSub;
        this.claimId = claimId;
        this.ipAddress = ipAddress;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getClaimantSub() {
        return claimantSub;
    }

    public Long getClaimId() {
        return claimId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
