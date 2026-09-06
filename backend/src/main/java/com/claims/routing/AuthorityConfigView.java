package com.claims.routing;

import java.math.BigDecimal;

/**
 * The supervisor-facing shape of one authority-config row (slice 7): the product code plus
 * the three editable parameters — the route level that classifies FNOL claims and the
 * L1/L2 monetary thresholds the authority gate evaluates. Returned by
 * {@code GET /api/config/authority} and by {@code PUT /api/config/authority/{productCode}}.
 */
public record AuthorityConfigView(String productCode, String routeLevel,
        BigDecimal l1LimitAmount, BigDecimal l2LimitAmount) {

    public static AuthorityConfigView from(AuthorityConfig config) {
        return new AuthorityConfigView(config.getProductCode(), config.getRouteLevel(),
                config.getL1LimitAmount(), config.getL2LimitAmount());
    }
}
