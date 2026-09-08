package com.claims.claim;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * V2-4: a first-class verification record on a claim. Rows are history — completing,
 * cancelling or re-opening never rewrites a finished row; the stage advances only when
 * the latest verification is COMPLETE. {@code evidenceRefs} is a free-text reference
 * list (attachment ids / cited refs), deliberately not a JSONB mapping.
 */
@Entity
@Table(name = "verification")
public class Verification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "type")
    private String type;

    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "notes")
    private String notes;

    @Column(name = "evidence_refs")
    private String evidenceRefs;

    @Column(name = "performed_by")
    private String performedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Verification() {
        // for JPA
    }

    public Verification(Long claimId, String type, String status, String performedBy,
            Instant startedAt) {
        this.claimId = claimId;
        this.type = type;
        this.status = status;
        this.performedBy = performedBy;
        this.startedAt = startedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(String evidenceRefs) {
        this.evidenceRefs = evidenceRefs;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(String performedBy) {
        this.performedBy = performedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
