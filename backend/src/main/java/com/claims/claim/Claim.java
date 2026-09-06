package com.claims.claim;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A claim as filed at FNOL. Sliced vertically: only columns slice 1 writes are mapped;
 * assignment/reserve/decision fields arrive with their slices.
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
}
