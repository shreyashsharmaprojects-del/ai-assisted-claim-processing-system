package com.claims.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A policy number that already exists (R1): the UNIQUE constraint is the guard. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicatePolicyNumberException extends RuntimeException {

    public DuplicatePolicyNumberException(String message) {
        super(message);
    }
}
