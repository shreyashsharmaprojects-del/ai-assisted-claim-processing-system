package com.claims.assignment;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Pure load-balance selection for slice 2: of the candidate adjusters of a claim's level,
 * pick the one with the fewest open claims; ties go to the lowest {@code app_user.id}
 * (see docs/decisions.md — load-balance tie-break). No database, no framework — the caller
 * supplies the current open-claim counts.
 */
public final class LoadBalancer {

    private LoadBalancer() {
    }

    /**
     * @param adjusterIds candidate adjuster ids of the claim's level (any order)
     * @param openCounts  current open-claim count per adjuster id; an id missing from the
     *                    map counts as zero open claims
     * @return the id of the least-loaded adjuster, or {@code null} when there are no
     *         candidates (a provisioning gap that leaves the claim unassigned)
     */
    public static Long leastLoaded(List<Long> adjusterIds, Map<Long, Long> openCounts) {
        return adjusterIds.stream()
                .sorted()
                .min(Comparator.comparingLong(id -> openCounts.getOrDefault(id, 0L)))
                .orElse(null);
    }
}
