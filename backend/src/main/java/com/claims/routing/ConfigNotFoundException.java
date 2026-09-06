package com.claims.routing;

/**
 * No authority-config row exists for the requested product code. A 404 — the supervisor
 * surface, so this is an ordinary not-found, not an existence-concealment case like claims.
 */
public class ConfigNotFoundException extends RuntimeException {

    public ConfigNotFoundException(String message) {
        super(message);
    }
}
