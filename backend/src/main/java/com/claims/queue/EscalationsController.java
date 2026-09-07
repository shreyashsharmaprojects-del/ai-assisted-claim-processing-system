package com.claims.queue;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.claims.api.PageRequest;
import com.claims.api.PageResult;

/**
 * The supervisor's escalation queue (slice 5, route-table row for {@code /api/escalations}):
 * claims in {@code ESCALATED_SUPERVISOR} that need a supervisor decision. The URL-level
 * role rule admits SUPERVISOR only — adjusters and claimants are blocked before this
 * controller is ever reached.
 *
 * <p>R4: paginated envelope {@code {content,page,size,totalElements,totalPages}} with
 * {@code page}/{@code size}/{@code q}/{@code status} query params.
 */
@RestController
@RequestMapping("/api/escalations")
public class EscalationsController {

    private final QueueService queueService;

    public EscalationsController(QueueService queueService) {
        this.queueService = queueService;
    }

    @GetMapping
    public PageResult<QueueClaimView> escalations(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status) {
        return queueService.supervisorEscalationQueue(PageRequest.of(page, size, q, status));
    }
}
