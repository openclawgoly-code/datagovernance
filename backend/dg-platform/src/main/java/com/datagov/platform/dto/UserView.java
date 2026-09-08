package com.datagov.platform.dto;

import java.time.Instant;

/**
 * 用户的对外视图。不含 passwordHash —— 散列虽不可逆,但泄漏后可离线爆破,
 * 没有任何理由出现在响应里。
 */
public record UserView(
        String id,
        String username,
        String displayName,
        String email,
        String phone,
        String status,
        boolean platformAdmin,
        Instant lastLoginAt,
        Instant createdAt
) {
}
