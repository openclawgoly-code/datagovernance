package com.datagov.platform.dto;

import java.time.Instant;

/**
 * 凭据的对外视图。
 *
 * <p><b>注意这里没有 payload 字段,而且永远不该有。</b>
 * {@code SPACE-MODEL.md} 的验收项之一是「凭据的查询/导出/日志接口一律不得
 * 返回凭据明文」—— 让类型上就不存在那个字段,比在每个接口里记得过滤要可靠。
 * 密文同样不返回:密文一旦泄漏,加上主密钥泄漏就是明文。
 */
public record CredentialView(
        String id,
        String name,
        String authType,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
}
