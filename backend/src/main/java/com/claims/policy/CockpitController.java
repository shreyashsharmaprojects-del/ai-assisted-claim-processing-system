package com.claims.policy;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claims.api.ClaimNotFoundException;

/**
 * V2-1 claimant cockpit: my policies + one policy's detail (covers with remaining
 * limits, rating parameters, clauses). Claimant-only; ownership is by holder email,
 * and a policy that is not mine reads as 404 (V1 404-not-403 discipline).
 *
 * <p>Holder email comes from the verified JWT claim when present; tests and
 * self-registered users without an email claim fall back to the subject-derived
 * placeholder, which simply owns no policies.
 */
@RestController
@RequestMapping("/api/policies")
public class CockpitController {

    private final CockpitService cockpit;

    public CockpitController(CockpitService cockpit) {
        this.cockpit = cockpit;
    }

    @GetMapping("/mine")
    public List<CockpitPolicyView> mine(Authentication authentication) {
        return cockpit.myPolicies(holderEmail(authentication));
    }

    @GetMapping("/{policyNumber}")
    public CockpitPolicyDetailView detail(@PathVariable String policyNumber,
            Authentication authentication) {
        CockpitPolicyDetailView detail = cockpit.policyDetail(
                policyNumber.trim().toUpperCase(java.util.Locale.ROOT),
                holderEmail(authentication));
        if (detail == null) {
            throw new ClaimNotFoundException();
        }
        return detail;
    }

    static String holderEmail(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            Object email = jwt.getClaim("email");
            if (email instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return (authentication == null ? "anonymous" : authentication.getName())
                + "@no-email.invalid";
    }
}
