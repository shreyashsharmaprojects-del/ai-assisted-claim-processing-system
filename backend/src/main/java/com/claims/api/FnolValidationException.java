package com.claims.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Claimant-submitted data failed validation; the form stays on screen. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class FnolValidationException extends RuntimeException {

    public FnolValidationException(String message) {
        super(message);
    }
}
