package com.claims.clause;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * V28 (V4 S1): a policy clause reference row. Clauses are reference data, not
 * claim state: the wording an adjuster reads when deciding a cover.
 * {@code coverCode} NULL means product-level (applies to every cover of the
 * product). Read-only mapping — nothing writes through this entity.
 */
@Entity
@Table(name = "policy_clause")
public class PolicyClause {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "cover_code")
    private String coverCode;

    @Column(name = "clause_ref", nullable = false)
    private String clauseRef;

    @Column(name = "clause_type", nullable = false)
    private String clauseType;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "clause_text", nullable = false)
    private String clauseText;

    @Column(name = "waiting_period_days")
    private Integer waitingPeriodDays;

    @Column(name = "sub_limit_amount")
    private BigDecimal subLimitAmount;

    @Column(name = "co_pay_percent")
    private BigDecimal coPayPercent;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected PolicyClause() {
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

    public String getClauseRef() {
        return clauseRef;
    }

    public String getClauseType() {
        return clauseType;
    }

    public String getTitle() {
        return title;
    }

    public String getClauseText() {
        return clauseText;
    }

    public Integer getWaitingPeriodDays() {
        return waitingPeriodDays;
    }

    public BigDecimal getSubLimitAmount() {
        return subLimitAmount;
    }

    public BigDecimal getCoPayPercent() {
        return coPayPercent;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
