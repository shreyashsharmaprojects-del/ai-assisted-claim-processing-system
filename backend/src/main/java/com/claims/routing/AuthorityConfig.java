package com.claims.routing;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Authority parameters per product code. {@code route_level} (the "complexity" parameter)
 * classifies FNOL claims at intake (slice 1); {@code l1_limit_amount} and
 * {@code l2_limit_amount} are the monetary thresholds the authority gate evaluates when the
 * indemnity figure is known (slice 4). V2-1 adds the L3 rung, the authority basis
 * (which aggregate the gate compares), and per-product SLA thresholds — read by the
 * gate/assignment/SLA consumers from V2-3 onward. Limits are per-claim amounts — no aggregate
 * exposure cap in v1 (see docs/decisions.md).
 */
@Entity
@Table(name = "authority_config")
public class AuthorityConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_code", nullable = false, unique = true)
    private String productCode;

    @Column(name = "route_level", nullable = false)
    private String routeLevel;

    @Column(name = "l1_limit_amount", nullable = false)
    private BigDecimal l1LimitAmount;

    @Column(name = "l2_limit_amount", nullable = false)
    private BigDecimal l2LimitAmount;

    /** V2-1: senior-adjuster rung limit (consumed by the V2-6 gate). */
    @Column(name = "l3_limit_amount")
    private BigDecimal l3LimitAmount;

    /** V2-1: which aggregate the gate compares (APPROVED_TOTAL | NET_PAYABLE_TOTAL). */
    @Column(name = "authority_basis", nullable = false)
    private String authorityBasis = "APPROVED_TOTAL";

    /** V2-1: SLA thresholds consumed by the V2-7 job (warning flags, breaches act). */
    @Column(name = "sla_warning_days", nullable = false)
    private Integer slaWarningDays = 2;

    @Column(name = "sla_breach1_days", nullable = false)
    private Integer slaBreach1Days = 3;

    @Column(name = "sla_breach1_action", nullable = false)
    private String slaBreach1Action = "ESCALATE_NEXT_LEVEL";

    @Column(name = "sla_breach2_days")
    private Integer slaBreach2Days;

    @Column(name = "sla_breach2_action")
    private String slaBreach2Action;

    protected AuthorityConfig() {
        // for JPA
    }

    public AuthorityConfig(String productCode, String routeLevel) {
        this.productCode = productCode;
        this.routeLevel = routeLevel;
    }

    public AuthorityConfig(String productCode, String routeLevel,
            BigDecimal l1LimitAmount, BigDecimal l2LimitAmount) {
        this.productCode = productCode;
        this.routeLevel = routeLevel;
        this.l1LimitAmount = l1LimitAmount;
        this.l2LimitAmount = l2LimitAmount;
    }

    public Long getId() {
        return id;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getRouteLevel() {
        return routeLevel;
    }

    public BigDecimal getL1LimitAmount() {
        return l1LimitAmount;
    }

    public BigDecimal getL2LimitAmount() {
        return l2LimitAmount;
    }

    public BigDecimal getL3LimitAmount() {
        return l3LimitAmount;
    }

    public String getAuthorityBasis() {
        return authorityBasis;
    }

    public Integer getSlaWarningDays() {
        return slaWarningDays;
    }

    public Integer getSlaBreach1Days() {
        return slaBreach1Days;
    }

    public String getSlaBreach1Action() {
        return slaBreach1Action;
    }

    public Integer getSlaBreach2Days() {
        return slaBreach2Days;
    }

    public String getSlaBreach2Action() {
        return slaBreach2Action;
    }

    /** Supervisor edit path (slice 7): the only write to an otherwise read-only row. */
    public void setRouteLevel(String routeLevel) {
        this.routeLevel = routeLevel;
    }

    /** Supervisor edit path (slice 7): the only write to an otherwise read-only row. */
    public void setL1LimitAmount(BigDecimal l1LimitAmount) {
        this.l1LimitAmount = l1LimitAmount;
    }

    /** Supervisor edit path (slice 7): the only write to an otherwise read-only row. */
    public void setL2LimitAmount(BigDecimal l2LimitAmount) {
        this.l2LimitAmount = l2LimitAmount;
    }
}
