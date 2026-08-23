package com.seatflow.common.domain.dto;

import java.util.List;

public record PagedResult<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean isFirst,
        boolean isLast
) {
    public static <T> PagedResult<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 1 : (int) Math.ceil((double) totalElements / (double) size);
        return new PagedResult<>(
                content != null ? List.copyOf(content) : List.of(),
                page,
                size,
                totalElements,
                totalPages,
                page == 0,
                page >= totalPages - 1
        );
    }
}
