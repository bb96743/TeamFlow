package com.teamflow.integration.security;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.integration.config.IntegrationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 凭据加解密（M12 RULE §四：禁止 Key/凭据明文存储）。
 *
 * <p>算法：AES/GCM/NoPadding，随机 12 字节 IV，128 位认证标签；密文格式
 * {@code v1:Base64(iv || ciphertext || tag)}。密钥来自环境变量
 * {@code TEAMFLOW_INTEGRATION_CREDENTIAL_KEY}（16/24/32 字节，Base64 或原文）。</p>
 *
 * <p><b>诚实边界</b>：本机无第三方 SaaS 凭据，真实凭据校验无法验证；本类只保证
 * 「落库的是密文、明文不回读、不写日志」。未配置密钥时加密/解密会明确失败，
 * 不会退化为明文存储。</p>
 */
@Component
public class CredentialCipher {

    private static final String VERSION = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IntegrationProperties properties;

    public CredentialCipher(IntegrationProperties properties) {
        this.properties = properties;
    }

    /** 是否已配置可用密钥（供接口展示"凭据能力是否就绪"，不泄露密钥本身）。 */
    public boolean configured() {
        try {
            key();
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    /** 加密明文凭据；返回 {@code v1:...} 密文。 */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return VERSION + Base64.getEncoder().encodeToString(payload);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("凭据加密失败：" + exception.getMessage());
        }
    }

    /** 解密；只在服务内部使用，不得返回给接口或写入日志。 */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        if (!cipherText.startsWith(VERSION)) {
            throw new BusinessException("凭据密文版本无法识别");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(cipherText.substring(VERSION.length()));
            if (payload.length <= IV_LENGTH) {
                throw new BusinessException("凭据密文不完整");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            System.arraycopy(payload, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("凭据解密失败（密钥不匹配或密文被篡改）");
        }
    }

    /** 凭据展示提示：只保留末 4 位，如 {@code ****abcd}。 */
    public String hintOf(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        String trimmed = plainText.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }

    /** 密钥指纹（SHA-256 前 8 位），仅用于排查密钥是否更换，不泄露密钥。 */
    public String keyFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKeyBytes());
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            return "";
        }
    }

    private SecretKeySpec key() {
        return new SecretKeySpec(rawKeyBytes(), "AES");
    }

    private byte[] rawKeyBytes() {
        String configured = properties.getCredentialKey();
        if (configured == null || configured.isBlank()) {
            throw new BusinessException("凭据加密密钥未配置：请设置环境变量 TEAMFLOW_INTEGRATION_CREDENTIAL_KEY"
                    + "（16/24/32 字节，Base64 或原文）；未配置时不会以明文保存凭据");
        }
        String value = configured.trim();
        byte[] bytes;
        if (value.startsWith("base64:")) {
            bytes = Base64.getDecoder().decode(value.substring("base64:".length()));
        } else {
            byte[] raw = value.getBytes(StandardCharsets.UTF_8);
            try {
                bytes = Base64.getDecoder().decode(value);
                if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
                    bytes = raw;
                }
            } catch (IllegalArgumentException exception) {
                bytes = raw;
            }
        }
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new BusinessException("凭据加密密钥长度不合法：需要 16/24/32 字节（当前 " + bytes.length + " 字节）");
        }
        return bytes;
    }
}
