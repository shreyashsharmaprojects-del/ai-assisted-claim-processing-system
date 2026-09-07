package com.claims.claim;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.claims.api.PageRequest;
import com.claims.api.PageResult;

/**
 * The claimant's own claim history ({@code GET /api/claims/mine}): every claim this
 * subject filed, newest first, public facts only. Declared BEFORE the
 * {@code /{claimNumber}} mapping in the same controller so "mine" is never mistaken for
 * a claim number — Spring prefers the exact match, but the ordering makes the intent
 * explicit and immune to pattern-matching surprises.
 *
 * <p>R4: paginated envelope {@code {content,page,size,totalElements,totalPages}} with
 * {@code page}/{@code size}/{@code q}/{@code status} query params; {@code q} searches
 * the caller's own rows only (the wall holds).
 */
@RestController
@RequestMapping("/api/claims")
public class MyClaimsController {

    private final MyClaimsService myClaimsService;

    public MyClaimsController(MyClaimsService myClaimsService) {
        this.myClaimsService = myClaimsService;
    }

    @GetMapping("/mine")
    public PageResult<MyClaimView> mine(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status) {
        return myClaimsService.mine(jwt.getSubject(), PageRequest.of(page, size, q, status));
    }
}
