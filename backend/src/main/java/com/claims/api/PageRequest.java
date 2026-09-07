package com.claims.api;

/**
 * Shared pagination + search input for list endpoints (R4: queue, escalations, mine;
 * policy-admin and outbox reuse this when they land). Controllers bind
 * {@code page} (0-based, default 0), {@code size} (default 25, max 100),
 * {@code q} (substring search, endpoint-scoped) and {@code status} (endpoint-scoped
 * exact match) directly from query params.
 */
public record PageRequest(int page, int size, String q, String status) {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;

    public static PageRequest of(Integer page, Integer size, String q, String status) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new PageRequest(safePage, safeSize, blankToNull(q), blankToNull(status));
    }

    /** Zero-based offset for {@code LIMIT ? OFFSET ?} queries. */
    public int offset() {
        return page * size;
    }

    /** True when a free-text search was supplied. */
    public boolean hasQuery() {
        return q != null;
    }

    /** True when a status filter was supplied. */
    public boolean hasStatus() {
        return status != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
