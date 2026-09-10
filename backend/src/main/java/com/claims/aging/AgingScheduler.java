package com.claims.aging;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled shell for the aging job (Flow 6). Runs daily at 03:00 tenant time
 * ({@code claims.timezone.default} — the zone is injected into {@link AgingService}, so a
 * single-zone deploy fires on the tenant's calendar, not the server's wall clock).
 * Integration tests disable this component ({@code claims.aging.enabled=false} in test
 * resources) and drive {@link AgingService#ageClaims(Instant)} with fixed instants instead
 * — a cron firing mid-suite could never be deterministic (the plan's named risk for this
 * slice).
 */
@Component
@ConditionalOnProperty(name = "claims.aging.enabled", havingValue = "true", matchIfMissing = true)
public class AgingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgingScheduler.class);

    private final AgingService agingService;

    public AgingScheduler(AgingService agingService) {
        this.agingService = agingService;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "${claims.timezone.default:Europe/London}")
    public void runAgingPass() {
        int aged = agingService.ageClaims(Instant.now());
        if (aged > 0) {
            log.info("Aging job escalated {} claim(s) past the service commitment", aged);
        }
    }
}
