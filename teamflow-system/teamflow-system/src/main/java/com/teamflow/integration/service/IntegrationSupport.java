package com.teamflow.integration.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * M12 服务层公共支撑：分页换算、操作人解析、取值归一、密钥生成与哈希。
 */
abstract class IntegrationSupport {

    /** API Key 明文前缀（便于识别与审计，不含敏感信息）。 */
    protected static final String API_KEY_PREFIX = "tfk_";

    private static final SecureRandom RANDOM = new SecureRandom();

    protected record Page(int pageNum, int pageSize, int offset) {
    }

    protected Page pageOf(PageQuery query) {
        PageQuery effective = query == null ? new PageQuery(null, null, null, null) : query;
        int pageNum = effective.normalizedPageNum();
        int pageSize = effective.normalizedPageSize();
        return new Page(pageNum, pageSize, (pageNum - 1) * pageSize);
    }

    protected String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : String.valueOf(authentication.getPrincipal());
    }

    protected String required(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new BusinessException(message);
        }
        return trimmed;
    }

    protected String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    protected String defaultString(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    protected Long numberAsLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    protected long longOf(Object value) {
        Long number = numberAsLong(value);
        return number == null ? 0L : number;
    }

    protected String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    protected boolean booleanOf(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return longOf(value) == 1L;
    }

    /** 生成 API Key 明文：{@code tfk_} + 32 字节随机数的 Base64URL（无填充）。 */
    protected String generateApiKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return API_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 十六进制哈希（只存哈希，不存明文）。 */
    protected String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new BusinessException("密钥哈希计算失败");
        }
    }

    /** 对外展示的密钥前缀（前 12 位）。 */
    protected String keyPrefixOf(String plainKey) {
        return plainKey.length() <= 12 ? plainKey : plainKey.substring(0, 12);
    }
}
