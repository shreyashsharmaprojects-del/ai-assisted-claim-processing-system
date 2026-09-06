package com.claims.policy;

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
}
