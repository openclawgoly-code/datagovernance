package com.datagov.common.id;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * 主键生成。
 *
 * <p>采用 ULID(48 位毫秒时间戳 + 80 位随机),Crockford Base32 编码为 26 字符:
 * <ul>
 *   <li><b>单调递增</b> —— 作为 PostgreSQL 主键时索引局部性远好于 UUIDv4,
 *       这在 P4 的执行记录(Execution)按时间大量写入时是刚需</li>
 *   <li><b>无中心依赖</b> —— 不需要发号器,Runtime 侧多执行器并行产生 ID 不冲突</li>
 *   <li><b>可读前缀</b> —— {@code ws_01J...} 一眼看出是哪类对象,排障时省一次查表</li>
 * </ul>
 */
public final class Ids {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /** 生成不带前缀的 26 字符 ULID。 */
    public static String ulid() {
        byte[] entropy = new byte[10];
        RANDOM.nextBytes(entropy);
        return encode(Instant.now().toEpochMilli(), entropy);
    }

    /**
     * 生成带类型前缀的 ID,例如 {@code Ids.of("ws")} → {@code ws_01J9X...}。
     *
     * @param prefix 对象类型短前缀:ws=Workspace, usr=User, role=Role,
     *               ds=DataSource, cred=Credential, cat=CatalogSnapshot
     */
    public static String of(String prefix) {
        return prefix + "_" + ulid();
    }

    private static String encode(long timestampMillis, byte[] entropy) {
        char[] out = new char[26];

        // 时间戳部分: 10 个 Base32 字符承载 50 位,取低 48 位
        long ts = timestampMillis;
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (ts & 0x1F)];
            ts >>>= 5;
        }

        // 随机部分: 80 位 → 16 个 Base32 字符
        int bitBuffer = 0;
        int bitCount = 0;
        int index = 10;
        for (byte b : entropy) {
            bitBuffer = (bitBuffer << 8) | (b & 0xFF);
            bitCount += 8;
            while (bitCount >= 5) {
                bitCount -= 5;
                out[index++] = CROCKFORD[(bitBuffer >>> bitCount) & 0x1F];
            }
        }
        return new String(out);
    }
}
