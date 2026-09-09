package com.claims.claim;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * S3: one row of a claim's required-documents checklist. Auto-inserted PENDING
 * at FNOL; an assignee links an attachment (RECEIVED) or waives (WAIVED) —
 * both audited. Advisory only: nothing gates decisions on incompleteness.
 */
@Entity
@Table(name = "claim_document_check")
public class ClaimDocumentCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "required_document_id", nullable = false)
    private Long requiredDocumentId;

    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    @Column(name = "attachment_id")
    private Long attachmentId;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected ClaimDocumentCheck() {
        // for JPA
    }

    public ClaimDocumentCheck(Long claimId, Long requiredDocumentId) {
        this.claimId = claimId;
        this.requiredDocumentId = requiredDocumentId;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Long getRequiredDocumentId() {
        return requiredDocumentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(Long attachmentId) {
        this.attachmentId = attachmentId;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }
}
