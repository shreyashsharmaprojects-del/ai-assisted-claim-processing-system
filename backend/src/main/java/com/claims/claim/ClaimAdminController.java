package com.claims.claim;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The supervisor's claim-admin surface (slice 7): reassign an open claim to a target level
 * and read a claim's immutable audit trail. SecurityConfig admits SUPERVISOR only at the
 * URL — an adjuster or claimant is a 403 before any claim logic runs; an unknown claim is a
 * 404.
 */
@RestController
@RequestMapping("/api/claims")
public class ClaimAdminController {

    private final ClaimAdminService claimAdminService;

    public ClaimAdminController(ClaimAdminService claimAdminService) {
        this.claimAdminService = claimAdminService;
    }

    @PostMapping("/{claimNumber}/reassign")
    public ClaimAssigneeView reassign(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String claimNumber, @RequestBody ReassignRequest request) {
        return claimAdminService.reassign(claimNumber, jwt.getSubject(), request.level());
    }

    @GetMapping("/{claimNumber}/audit")
    public List<AuditEntryView> audit(@PathVariable String claimNumber) {
        return claimAdminService.audit(claimNumber);
    }

    /** The target routing level; the claim is routed to that level's least-loaded adjuster. */
    public record ReassignRequest(String level) {
    }
}
