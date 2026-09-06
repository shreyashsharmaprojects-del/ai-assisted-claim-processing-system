package com.claims.claim;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claims.api.ClaimNotFoundException;

/**
 * Claimant status surface (slice 3, E2E journey 2): a claimant opens their own claim by
 * number and gets only the claimant view. Someone else's claim number — or a number that
 * does not exist — is a 404, never a 403 (the response never reveals the number exists).
 */
@RestController
@RequestMapping("/api/claims")
public class ClaimantStatusController {

    private final ClaimRepository claims;

    public ClaimantStatusController(ClaimRepository claims) {
        this.claims = claims;
    }

    @GetMapping("/{claimNumber}")
    public ClaimantClaimView status(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String claimNumber) {
        Claim claim = claims.findByClaimNumber(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!claim.getClaimantSub().equals(jwt.getSubject())) {
            throw new ClaimNotFoundException();
        }
        return ClaimantClaimView.from(claim);
    }
}
