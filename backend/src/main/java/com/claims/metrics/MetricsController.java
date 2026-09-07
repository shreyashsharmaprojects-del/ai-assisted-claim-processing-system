package com.claims.metrics;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * R3 scrape endpoint: {@code GET /api/metrics} returns the {@link ClaimsMetrics}
 * snapshot as JSON. Supervisor-scoped via SecurityConfig (never public; readiness and
 * liveness stay public). Shape:
 * {@code {claims_fnol_total, claims_fnol_rejected_total{reason},
 * claims_decisions_total{outcome}, claims_escalations_total{target},
 * claims_queue_depth{level}, claims_outbox_pending, claims_outbox_failed_total}}.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final ClaimsMetrics metrics;

    public MetricsController(ClaimsMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }
}
