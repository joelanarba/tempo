package com.tempoapi.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable pagination envelope. Returning Spring's {@code Page} directly serializes an
 * unstable internal shape; this record fixes the contract clients depend on.
 *
 * @param <T> element type
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
