package com.claims.api;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Request tracing: every HTTP request gets a short {@code X-Request-Id} (generated when
 * the caller does not send one), echoed back on the response and bound to the logging
 * MDC for the request's lifetime. When a user reports "Reference: a1b2c3d4" from an
 * error screen, ops greps the logs for {@code rid=a1b2c3d4} and finds the exact
 * request — no timestamps, no guesswork.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "rid";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        } else {
            requestId = requestId.replaceAll("[^a-zA-Z0-9_-]", "").substring(0,
                    Math.min(requestId.length(), 36));
            if (requestId.isBlank()) {
                requestId = UUID.randomUUID().toString().substring(0, 8);
            }
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
