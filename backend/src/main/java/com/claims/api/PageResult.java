package com.claims.api;

import java.util.List;

/**
 * Shared paginated response envelope (R4). JSON shape:
 * {@code {content:[...], page:0, size:25, totalElements:30, totalPages:2}}.
 * The row type inside {@code content} is endpoint-specific (queue rows, mine rows,
 * …); only the envelope is shared.
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements,
        int totalPages) {

    public static <T> PageResult<T> of(List<T> content, PageRequest request,
            long totalElements) {
        int totalPages = totalElements == 0 ? 0
                : (int) Math.ceil((double) totalElements / request.size());
        return new PageResult<>(List.copyOf(content), request.page(), request.size(),
                totalElements, totalPages);
    }
}
