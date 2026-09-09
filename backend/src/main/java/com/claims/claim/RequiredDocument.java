package com.claims.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * S3: a required document for a product (claim-level rows carry a NULL
 * cover_code). Seeded per product code by V19; the per-claim checklist
 * ({@link ClaimDocumentCheck}) references these rows.
 */
@Entity
@Table(name = "required_document")
public class RequiredDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "cover_code")
    private String coverCode;

    @Column(name = "doc_key", nullable = false)
    private String docKey;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected RequiredDocument() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getCoverCode() {
        return coverCode;
    }

    public String getDocKey() {
        return docKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
