package com.claims.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The supplied policy number + holder details match no seeded policy. Returned as 404 so
 * the response never distinguishes "policy exists but holder mismatch" from "no such
 * policy".
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PolicyMismatchException extends RuntimeException {

    public PolicyMismatchException(String message) {
        super(message);
    }
}
