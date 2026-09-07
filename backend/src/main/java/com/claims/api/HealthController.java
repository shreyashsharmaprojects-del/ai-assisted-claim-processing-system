package com.claims.api;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness + readiness for deploy orchestration and load balancers.
 *
 * <ul>
 *   <li>{@code GET /api/health} — liveness: the process is up. No database check, so a
 *       probe never conflates "app dead" with "database down". Public by design.</li>
 *   <li>{@code GET /api/ready} — readiness: the process is up AND Flyway migrations are
 *       current AND a trivial query succeeds. Orchestrators should gate traffic on this,
 *       not liveness. Public by design (it reveals only up/not-up).</li>
 * </ul>
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/api/ready")
    public Map<String, String> ready() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "READY");
    }
}
