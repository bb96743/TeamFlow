package com.teamflow.project.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.project.mapper.ProjectPlatformMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * M14 项目服务层公共支撑：分页换算、日期解析、数值口径、操作人解析、状态机异常转业务异常。
 */
abstract class ProjectServiceSupport {

    protected static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    protected static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    protected final ProjectPlatformMapper platformMapper;

    protected ProjectServiceSupport(ProjectPlatformMapper platformMapper) {
        this.platformMapper = platformMapper;
    }

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

    protected Long currentUserId() {
        return platformMapper.selectUserIdByUsername(currentUsername());
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
        return Long.valueOf(String.valueOf(value));
    }

    protected long longOf(Object value) {
        Long number = numberAsLong(value);
        return number == null ? 0L : number;
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

    protected BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.multiply(new BigDecimal("100")).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    protected LocalDate parseDate(String value, String label) {
        String text = required(value, label + "不能为空");
        try {
            return LocalDate.parse(text, DATE_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(label + "格式应为 yyyy-MM-dd");
        }
    }

    protected LocalDate parseNullableDate(String value, String label) {
        String text = trimToNull(value);
        return text == null ? null : parseDate(text, label);
    }

    /** 状态机异常（IllegalArgumentException）统一转业务异常，保证接口返回 400 且文案可读。 */
    protected void requireTransition(Runnable transitionCheck) {
        try {
            transitionCheck.run();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(exception.getMessage());
        }
    }

    protected Map<String, Object> mapOf(String[] keys, Object... values) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) {
            row.put(keys[i], i < values.length ? values[i] : null);
        }
        return row;
    }
}
