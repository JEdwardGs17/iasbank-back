package com.iasbank.core.domain.dto;

import java.util.List;

/**
 * One page of results, independent of any framework.
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    public int totalPages() {
        return (int) Math.ceil((double) totalElements / size);
    }
}
