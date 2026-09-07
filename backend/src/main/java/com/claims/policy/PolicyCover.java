package com.claims.policy;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * V2-1: one opted cover on a policy (the ~5-cover health set, or the single degenerate
 * cover on motor/legacy rows). Sub-limits constrain assessment/approval (V2-5), never
 * FNOL filing (locked rule 1): no filing-time check lives here.
 */
@Entity
@Table(name = "policy_cover")
public class PolicyCover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "cover_code", nullable = false)
    private String coverCode;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "sub_limit", nullable = false)
    private BigDecimal subLimit;

    @Column(name = "deductible_default", nullable = false)
    private BigDecimal deductibleDefault;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected PolicyCover() {
        // for JPA
    }

    public PolicyCover(Long policyId, String coverCode, String displayName,
            BigDecimal subLimit, BigDecimal deductibleDefault, int sortOrder) {
        this.policyId = policyId;
        this.coverCode = coverCode;
        this.displayName = displayName;
        this.subLimit = subLimit;
        this.deductibleDefault = deductibleDefault;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getPolicyId() {
        return policyId;
    }

    public String getCoverCode() {
        return coverCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public BigDecimal getSubLimit() {
        return subLimit;
    }

    public BigDecimal getDeductibleDefault() {
        return deductibleDefault;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
