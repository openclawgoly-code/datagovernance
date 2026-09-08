package com.datagov.common.api;

import java.util.List;

/**
 * 分页结果。所有列表类查询统一返回该结构,避免每个 Space 各自发明分页协议。
 */
public record PageResult<T>(
        List<T> records,
        long total,
        long page,
        long size
) {

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        return new PageResult<>(records, total, page, size);
    }

    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(List.of(), 0L, page, size);
    }

    public long totalPages() {
        return size <= 0 ? 0 : (total + size - 1) / size;
    }
}
