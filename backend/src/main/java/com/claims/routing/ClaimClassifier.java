package com.claims.routing;

import java.util.List;

/**
 * Pure classification logic for slice 1: a claim is routed L1 or L2 by looking up the
 * policy's product code in the configurable authority table (the "complexity" parameter).
 */
public final class ClaimClassifier {

    public static final String L1 = "L1";
    public static final String L2 = "L2";

    private ClaimClassifier() {
    }

    /**
     * @return the route level for {@code productCode}, or {@code null} when no config row
     *         exists for that product code (a server configuration gap).
     */
    public static String routeLevelFor(String productCode, List<AuthorityConfig> configs) {
        for (AuthorityConfig config : configs) {
            if (config.getProductCode().equals(productCode)) {
                return config.getRouteLevel();
            }
        }
        return null;
    }
}
