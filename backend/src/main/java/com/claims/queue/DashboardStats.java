package com.claims.queue;

import java.math.BigDecimal;

/**
 * One number tile on the supervisor operations dashboard (GET /api/dashboard): the
 * counts an ops lead asks first — open load, what waits on them, what aged, what
 * closed — plus the money committed. Computed in a single JDBC pass per tile group so
 * the dashboard never N+1s the claim table.
 */
public record DashboardStats(long openClaims, long unassignedClaims, long underReviewClaims,
        long escalatedClaims, long closedClaims, long agedToL2Last7Days,
        long agedToSupervisorLast7Days, BigDecimal approvedThisMonth) {
}
