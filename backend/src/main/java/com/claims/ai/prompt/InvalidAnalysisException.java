package com.claims.ai.prompt;

/**
 * Thrown when a model reply fails strict output validation. The naming style
 * mirrors {@code com.claims.api.InvalidRequestException} (a 400-style message
 * naming the violation), but this type lives in the AI package so callers
 * decide the mapping.
 */
public class InvalidAnalysisException extends RuntimeException {

    public InvalidAnalysisException(String message) {
        super(message);
    }
}
