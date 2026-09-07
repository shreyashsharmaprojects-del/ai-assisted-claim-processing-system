package com.claims.policy;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * V2-1 claimant cockpit shape: full policy detail — covers with remaining limits,
 * rating parameters (the numbers that define the cover), and clauses
 * (covered/excluded/scope). Claimant-safe: holder email stays out.
 */
public record CockpitPolicyDetailView(CockpitPolicyView policy, List<CockpitCoverView> covers,
        JsonNode ratingParams, JsonNode clauses) {
}
