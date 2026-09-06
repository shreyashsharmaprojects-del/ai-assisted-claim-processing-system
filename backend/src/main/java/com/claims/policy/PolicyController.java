package com.claims.policy;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read path for the walking skeleton. No auth in slice 0 — that starts with slice 1.
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
