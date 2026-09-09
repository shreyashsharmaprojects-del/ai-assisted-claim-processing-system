package com.claims.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Maps the slice-1..3 domain failures to HTTP responses with a message the user can act on. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final com.claims.metrics.ClaimsMetrics metrics;

    public ApiExceptionHandler(com.claims.metrics.ClaimsMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(FnolValidationException.class)
    public ResponseEntity<ErrorResponse> badRequest(FnolValidationException ex) {
        metrics.fnolRejected("validation");
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(PolicyMismatchException.class)
    public ResponseEntity<ErrorResponse> notFound(PolicyMismatchException ex) {
        metrics.fnolRejected("policy_mismatch");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(ClaimNotFoundException.class)
    public ResponseEntity<ErrorResponse> claimNotFound(ClaimNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(com.claims.routing.ConfigNotFoundException.class)
    public ResponseEntity<ErrorResponse> configNotFound(
            com.claims.routing.ConfigNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(InvalidRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * R1: the {@code policy_number} UNIQUE constraint is the duplicate guard — surface it
     * as a clean 409, never a 500 with driver internals.
     */
    @ExceptionHandler(DuplicatePolicyNumberException.class)
    public ResponseEntity<ErrorResponse> duplicatePolicy(DuplicatePolicyNumberException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * Client errors on the JSON endpoints (the first @RequestBody surface): a malformed or
     * empty body, or a path variable of the wrong type, is the caller's fault — never a 500.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> unreadableRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("The request could not be read: check the body and path values."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse("Method not allowed on this endpoint."));
    }

    @ExceptionHandler(UnroutableException.class)
    public ResponseEntity<ErrorResponse> unroutable(UnroutableException ex) {
        log.error("Claim could not be routed", ex);
        metrics.fnolRejected("unroutable");
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

    /**
     * Rate limiting (FNOL flood protection): 429 with a Retry-After hint and a message the
     * caller can act on (wait, then retry). The handler logs at WARN with the claimant
     * subject redacted to a short hash — rate-limit events are abuse telemetry, not PII.
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ErrorResponse> rateLimited(RateLimitedException ex) {
        log.warn("Rate limit hit; retry after {}s", ex.getRetryAfterSeconds());
        metrics.fnolRejected("rate_limited");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * V2-2: the duplicate-FNOL guard — 409 with the existing claim number both in the
     * message (humans) and as a field (routerLink). Additive: old {message} clients
     * are unaffected (NON_NULL view, Map body here).
     */
    @ExceptionHandler(DuplicateFnolException.class)
    public ResponseEntity<java.util.Map<String, String>> duplicateFnol(
            DuplicateFnolException ex) {
        metrics.fnolRejected("duplicate");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of(
                "message", ex.getMessage(), "claimNumber", ex.getClaimNumber()));
    }

    /**
     * V21 (V3 S5): optimistic-concurrency conflicts (thrown explicitly on a stale
     * {@code expectedVersion}) — one shape everywhere: 409 CONFLICT with the
     * reload-and-retry message.
     */
    @ExceptionHandler({jakarta.persistence.OptimisticLockException.class,
            org.springframework.orm.ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<java.util.Map<String, String>> versionConflict(Exception ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of(
                "error", "CONFLICT",
                "message", "This claim changed since you opened it. Reload and retry."));
    }

    /** Anything unexpected: log the cause, never leak internals to the caller. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception ex) {
        String reference = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.error("Unexpected error [ref={}]", reference, ex);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse(
                        "Something went wrong. Please try again. (Reference: " + reference + ")"));
    }

    public record ErrorResponse(String message) {
    }
}
