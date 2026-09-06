package com.claims.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for load-balance selection (slice 2): fewest open claims wins, ties go to the
 * lowest adjuster id, an adjuster with no recorded open claims counts as zero, and an empty
 * pool leaves the claim unassigned. No database, no Spring.
 */
class LoadBalancerTest {

    @Test
    void adjusterWithFewestOpenClaimsWins() {
        assertEquals(2L, LoadBalancer.leastLoaded(
                List.of(1L, 2L, 3L), Map.of(1L, 3L, 2L, 1L, 3L, 5L)));
    }

    @Test
    void tiesGoToTheLowestAdjusterId() {
        assertEquals(1L, LoadBalancer.leastLoaded(
                List.of(1L, 2L, 3L), Map.of(1L, 2L, 2L, 2L, 3L, 2L)));
    }

    @Test
    void adjusterWithNoRecordedCountsAsZeroOpenClaims() {
        // id 2 has no row in the counts map -> 0 open claims, so it wins over id 1 (1 open).
        assertEquals(2L, LoadBalancer.leastLoaded(List.of(1L, 2L), Map.of(1L, 1L)));
    }

    @Test
    void candidateOrderDoesNotAffectSelection() {
        assertEquals(1L, LoadBalancer.leastLoaded(
                List.of(3L, 1L, 2L), Map.of(3L, 0L, 1L, 0L, 2L, 0L)));
    }

    @Test
    void emptyCandidatePoolReturnsNull() {
        assertNull(LoadBalancer.leastLoaded(List.of(), Map.of()));
    }
}
