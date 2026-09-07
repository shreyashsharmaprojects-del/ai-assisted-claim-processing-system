package com.claims.policy;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Policy reference data for the FNOL form. Authenticated-only: the list carries
 * policyholder names (personal data), so anonymous callers get a 401 and pick a policy
 * from their own documents instead. Any signed-in role may read it — claimants need it
 * to file, adjusters to verify coverage context.
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
