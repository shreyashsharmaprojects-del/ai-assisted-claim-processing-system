package com.claims.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Maps the slice-1 domain failures to HTTP responses with a message the user can act on. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(FnolValidationException.class)
    public ResponseEntity<ErrorResponse> badRequest(FnolValidationException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(PolicyMismatchException.class)
    public ResponseEntity<ErrorResponse> notFound(PolicyMismatchException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(UnroutableException.class)
    public ResponseEntity<ErrorResponse> unroutable(UnroutableException ex) {
        log.error("Claim could not be routed", ex);
        // Log the detail; the caller only gets a generic message (never internal config).
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("We could not route your claim. Please try again."));
    }

    /**
     * The multipart layer enforces a hard ceiling above the app-level photo cap
     * (see application.properties); a request that trips it is a client error, not a 500.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> uploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("Uploaded files are too large."));
    }

    /** Anything unexpected: log the cause, never leak internals to the caller. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("Something went wrong. Please try again."));
    }

    public record ErrorResponse(String message) {
    }
}
