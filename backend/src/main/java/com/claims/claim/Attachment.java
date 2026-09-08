package com.claims.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A claimant evidence photo stored on disk; the row records where and what it was. */
@Entity
@Table(name = "attachment")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    /**
     * V16: human document label (dropdown choice or free-text custom name). Null on
     * pre-V16 rows; the download name stays the stored original filename.
     */
    @Column(name = "label")
    private String label;

    protected Attachment() {
        // for JPA
    }

    public Attachment(Long claimId, String storagePath, String contentType, String originalName) {
        this(claimId, storagePath, contentType, originalName, null);
    }

    public Attachment(Long claimId, String storagePath, String contentType, String originalName,
            String label) {
        this.claimId = claimId;
        this.storagePath = storagePath;
        this.contentType = contentType;
        this.originalName = originalName;
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getContentType() {
        return contentType;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
