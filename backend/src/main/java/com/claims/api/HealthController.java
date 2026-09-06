package com.claims.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness endpoint for deploy orchestration and load balancers. It reports only that the
 * process is up — deliberately no database check, so a probe never conflates "app dead"
 * with "database down" (that is a readiness concern; the E2E web-server probe uses
 * {@code GET /api/policies}, which does touch the database). Public by design.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
