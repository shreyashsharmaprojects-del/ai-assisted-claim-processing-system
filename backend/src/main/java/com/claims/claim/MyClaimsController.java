package com.claims.claim;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The claimant's own claim history ({@code GET /api/claims/mine}): every claim this
 * subject filed, newest first, public facts only. Declared BEFORE the
 * {@code /{claimNumber}} mapping in the same controller so "mine" is never mistaken for
 * a claim number — Spring prefers the exact match, but the ordering makes the intent
 * explicit and immune to pattern-matching surprises.
 */
@RestController
@RequestMapping("/api/claims")
public class MyClaimsController {

    private final MyClaimsService myClaimsService;

    public MyClaimsController(MyClaimsService myClaimsService) {
        this.myClaimsService = myClaimsService;
    }

    @GetMapping("/mine")
    public List<MyClaimView> mine(@AuthenticationPrincipal Jwt jwt) {
        return myClaimsService.mine(jwt.getSubject());
    }
}
