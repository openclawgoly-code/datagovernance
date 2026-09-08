package com.datagov.platform.dto;

import java.time.Instant;

/**
 * 空间(租户)的对外视图。
 *
 * <p>不含 secretKey —— 密钥只在创建与轮换时返回一次明文,之后任何查询都拿不到。
 */
public record WorkspaceView(
        String id,
        String code,
        String name,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
