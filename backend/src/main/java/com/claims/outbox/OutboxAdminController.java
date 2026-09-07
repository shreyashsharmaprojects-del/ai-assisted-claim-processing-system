package com.claims.outbox;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.claims.api.InvalidRequestException;
import com.claims.api.PageRequest;
import com.claims.api.PageResult;

/**
 * R2 outbox admin (supervisor-only via SecurityConfig): paginated row list (newest first,
 * status filter) and retry of a FAILED row back to PENDING. Retrying a non-FAILED row is
 * a 400 — only genuinely failed mail goes back in the queue.
 */
@RestController
@RequestMapping("/api/outbox")
public class OutboxAdminController {

    private final EmailOutboxRepository outbox;

    public OutboxAdminController(EmailOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @GetMapping
    public PageResult<OutboxRowView> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status) {
        return outbox.adminList(PageRequest.of(page, size, q, status));
    }

    @PostMapping("/{id}/retry")
    public OutboxRowView retry(@PathVariable long id) {
        if (!outbox.retry(id)) {
            throw new InvalidRequestException(
                    "Only a FAILED outbox row can be retried.");
        }
        return outbox.findRow(id);
    }
}
