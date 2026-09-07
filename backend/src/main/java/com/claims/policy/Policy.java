package com.claims.policy;

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
 * A seeded, read-only policy. The coverage column is JSONB and is deliberately not
 * mapped in the skeleton — nothing reads it yet.
 */
@Entity
@Table(name = "policy")
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_number", nullable = false, unique = true)
    private String policyNumber;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    @Column(name = "holder_email", nullable = false)
    private String holderEmail;

    /** ACTIVE (fileable) or RETIRED (history-only, rejected at FNOL) — R1, V9. */
    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    /**
     * V2-1: policy-period cap. Remaining benefit is derived
     * ({@code sum_insured - prior net payables}), never stored.
     */
    @Column(name = "sum_insured", insertable = false, updatable = false)
    private BigDecimal sumInsured;

    /**
     * V2-1: the numbers that define the cover (room-rent cap, waiting periods, zone…).
     * Mapped LONGVARCHAR (never read through JPA — the cockpit reads jsonb via JDBC);
     * only status transitions write through this entity, and Postgres accepts the
     * default '{}' without a cast on insert paths used here.
     */
    @Column(name = "rating_params", columnDefinition = "jsonb", insertable = false,
            updatable = false)
    private String ratingParams;

    /** V2-1: {covered[], excluded[], scope} clause wording shown in the cockpit. */
    @Column(name = "clauses", columnDefinition = "jsonb", insertable = false,
            updatable = false)
    private String clauses;

    @Column(name = "valid_from", insertable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", insertable = false, updatable = false)
    private LocalDate validTo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Policy() {
        // for JPA
    }

    /** For tests and non-JPA construction; the database assigns the id. */
    public Policy(String policyNumber, String productCode, String holderName, String holderEmail) {
        this.policyNumber = policyNumber;
        this.productCode = productCode;
        this.holderName = holderName;
        this.holderEmail = holderEmail;
    }

    public Long getId() {
        return id;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getHolderName() {
        return holderName;
    }

    public String getHolderEmail() {
        return holderEmail;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isRetired() {
        return "RETIRED".equals(status);
    }

    public boolean isExpired() {
        return "EXPIRED".equals(status);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public BigDecimal getSumInsured() {
        return sumInsured;
    }

    public String getRatingParams() {
        return ratingParams;
    }

    public String getClauses() {
        return clauses;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }
}
