package com.claims.queue;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The supervisor's escalation queue (slice 5, route-table row for {@code /api/escalations}):
 * claims in {@code ESCALATED_SUPERVISOR} that need a supervisor decision. The URL-level
 * role rule admits SUPERVISOR only — adjusters and claimants are blocked before this
 * controller is ever reached.
 */
@RestController
@RequestMapping("/api/escalations")
public class EscalationsController {

    private final QueueService queueService;

    public EscalationsController(QueueService queueService) {
        this.queueService = queueService;
    }

    @GetMapping
    public List<QueueClaimView> escalations() {
        return queueService.supervisorEscalationQueue();
    }
}
