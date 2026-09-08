package com.claims.policy;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legacy V1 reference list (GET /api/policies). SUPERVISOR-ONLY since the
 * privacy hardening: the rows carry every customer's policy number + holder
 * name, so claimants and adjusters are 403 here (SecurityConfig). Claimants
 * read their own rows via the cockpit ({@code /mine}); the supervisor book
 * UI reads {@code /admin}; the FNOL cover picker reads filing-covers.
 */
@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicyRepository repository;

    public PolicyController(PolicyRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<PolicySummary> list() {
        return PolicyViewMapper.toSummaries(repository.findAll());
    }
}
