package com.teamflow.okr.service;

import com.teamflow.common.domain.PageQuery;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * M02 服务层公共支撑：分页换算、请求值归一化、操作人解析。
 *
 * <p>仅承载与业务无关的样板逻辑，避免各 Service 重复实现。</p>
 */
abstract class OkrServiceSupport {

    /** 分页参数换算结果。 */
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
            throw new com.teamflow.common.exception.BusinessException(message);
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
        return Long.valueOf(String.valueOf(value));
    }

    protected BigDecimal numberAsBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    protected BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    protected BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    protected String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "draft" -> "草稿";
            case "active" -> "进行中";
            case "done" -> "已完成";
            case "archived" -> "已归档";
            default -> status;
        };
    }
}
