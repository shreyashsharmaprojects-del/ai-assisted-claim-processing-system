package com.claims.queue;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The supervisor operations dashboard ({@code GET /api/dashboard}): aggregate counts +
 * the monthly approved total. SUPERVISOR-only at the URL (SecurityConfig) — adjusters
 * and claimants are 403 before any query runs.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public DashboardStats dashboard() {
        return dashboardService.stats();
    }
}
