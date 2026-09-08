package com.datagov.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 实现。
 *
 * <p>密文格式: {@code v1:BASE64(IV ‖ CIPHERTEXT ‖ TAG)},IV 12 字节随机、TAG 128 位。
 * 带版本前缀是为了将来换算法(例如信创场景改用 SM4-GCM)时能平滑共存:
 * 解密按前缀分派,加密始终用当前版本,历史密文可后台惰性重写。
 *
 * <p>主密钥由部署方通过配置注入(见 {@code dg.security.secret-key})。
 * 任何情况下都不得把主密钥写入代码库或迁移脚本。
 */
public class AesGcmSecretCipher implements SecretCipher {

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param masterKey 主密钥。任意长度字符串,内部以 SHA-256 派生出 256 位密钥,
     *                  这样运维可以填一句口令而不必凑够 32 字节。
     */
    public AesGcmSecretCipher(String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalArgumentException("主密钥 dg.security.secret-key 未配置");
        }
        this.key = new SecretKeySpec(sha256(masterKey), "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        if (!isEncrypted(ciphertext)) {
            throw new IllegalStateException("密文格式不合法或算法版本不受支持");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (combined.length <= IV_LENGTH) {
                throw new IllegalStateException("密文长度异常");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // GCM 校验失败会走到这里 —— 密文被篡改或主密钥不匹配
            throw new IllegalStateException("解密失败: 密钥不匹配或密文已被篡改", e);
        }
    }

    @Override
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
