package com.claims.routing;

import java.math.BigDecimal;

/**
 * The supervisor-facing shape of one authority-config row (slice 7): the product code plus
 * the three editable parameters — the route level that classifies FNOL claims and the
 * L1/L2 monetary thresholds the authority gate evaluates. Returned by
 * {@code GET /api/config/authority} and by {@code PUT /api/config/authority/{productCode}}.
 * V2-1 exposes the L3 limit, authority basis, and SLA thresholds read-only; editing
 * them arrives in V2-8 (the PUT contract is unchanged so V1 clients keep working).
 */
public record AuthorityConfigView(String productCode, String routeLevel,
        BigDecimal l1LimitAmount, BigDecimal l2LimitAmount, BigDecimal l3LimitAmount,
        String authorityBasis, Integer slaWarningDays, Integer slaBreach1Days,
        String slaBreach1Action, Integer slaBreach2Days, String slaBreach2Action) {

    public AuthorityConfigView(String productCode, String routeLevel,
            BigDecimal l1LimitAmount, BigDecimal l2LimitAmount) {
        this(productCode, routeLevel, l1LimitAmount, l2LimitAmount, null, "APPROVED_TOTAL",
                2, 3, "ESCALATE_NEXT_LEVEL", null, null);
    }

    public static AuthorityConfigView from(AuthorityConfig config) {
        return new AuthorityConfigView(config.getProductCode(), config.getRouteLevel(),
                config.getL1LimitAmount(), config.getL2LimitAmount(),
                config.getL3LimitAmount(), config.getAuthorityBasis(),
                config.getSlaWarningDays(), config.getSlaBreach1Days(),
                config.getSlaBreach1Action(), config.getSlaBreach2Days(),
                config.getSlaBreach2Action());
    }
}
