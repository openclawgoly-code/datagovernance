package com.datagov.common.crypto;

/**
 * 敏感字段加解密。
 *
 * <p>用于数据源密码、接口认证凭据(功能 4)、空间密钥(功能 28)等一切
 * <b>需要还原明文才能使用</b>的秘密 —— 与用户口令不同,它们不能用单向散列。
 *
 * <p>Governance Space(P4)会要求"凭据不得以明文出现在日志、审计与导出中",
 * 该接口是那条约束在 P1 阶段的落点。
 */
public interface SecretCipher {

    /** 加密明文,返回自带算法版本前缀的密文串。入参为 null 时返回 null。 */
    String encrypt(String plaintext);

    /** 解密密文。入参为 null 时返回 null;密文被篡改时抛 {@link IllegalStateException}。 */
    String decrypt(String ciphertext);

    /** 判断一个字符串是否已是本实现产生的密文,便于迁移期幂等处理。 */
    boolean isEncrypted(String value);
}
