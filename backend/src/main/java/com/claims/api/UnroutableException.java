package com.claims.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Server-side configuration gap (e.g. no routing row for a product code). */
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class UnroutableException extends RuntimeException {

    public UnroutableException(String message) {
        super(message);
    }
}
