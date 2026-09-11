package com.claims.clause;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.claims.claim.Authorities;

/**
 * V28 (V4 S1): the policy-clause reference surface. The catalogue GET admits
 * internal staff (browsing by product, not claim access); the claim GET serves
 * the loss-date-selected wording for the assignee/supervisor (404 otherwise).
 * Claimant vs adjuster URL roles stay in SecurityConfig.
 */
@RestController
@RequestMapping("/api")
public class PolicyClauseController {

    private final PolicyClauseService clauses;

    public PolicyClauseController(PolicyClauseService clauses) {
        this.clauses = clauses;
    }

    @GetMapping("/clauses")
    public List<PolicyClauseService.ClauseView> catalogue(
            @RequestParam String productCode,
            @RequestParam(required = false) String coverCode) {
        return clauses.catalogue(productCode, coverCode);
    }

    @GetMapping("/claims/{claimNumber}/policy-clauses")
    public List<PolicyClauseService.ClauseView> forClaim(
            @AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String claimNumber) {
        return clauses.forClaim(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication));
    }
}
