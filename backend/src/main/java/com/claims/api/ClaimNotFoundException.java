package com.claims.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A claim that does not exist — or exists but the caller may not see it. Returned as 404
 * either way so a response never reveals whether a claim number exists (the plan's
 * "unauthorized access returns 404, never 403" rule for cross-tenant / non-assignee
 * access).
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ClaimNotFoundException extends RuntimeException {

    public ClaimNotFoundException() {
        super("Claim not found.");
    }
}
