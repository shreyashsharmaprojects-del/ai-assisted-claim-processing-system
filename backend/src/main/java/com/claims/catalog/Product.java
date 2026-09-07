package com.claims.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * V2-1 product catalog. V1 product codes (HOME/AUTO) are rows here too — the code is
 * the authority-config key, so the catalog never diverges from routing.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "family", nullable = false)
    private String family;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "description", nullable = false)
    private String description;

    protected Product() {
        // for JPA
    }

    public Product(String code, String family, String displayName, String description) {
        this.code = code;
        this.family = family;
        this.displayName = displayName;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getFamily() {
        return family;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
