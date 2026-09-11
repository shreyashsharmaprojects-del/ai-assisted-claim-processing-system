package com.claims.ai.client;

/**
 * Typed failure from the AI provider seam. The message is always ops/user-safe:
 * it carries a status code or a failure category plus a short body prefix, and
 * never the API key, the prompt text, or claim PII. The cause (when present)
 * is for server-side logs only.
 */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
