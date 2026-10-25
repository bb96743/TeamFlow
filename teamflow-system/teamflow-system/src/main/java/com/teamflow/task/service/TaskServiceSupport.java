package com.teamflow.task.service;

import com.teamflow.common.domain.PageQuery;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * M03 服务层公共支撑：分页换算、请求值归一化、操作人解析。
 *
 * <p>只承载与业务无关的样板逻辑（与 M02 的 {@code OkrServiceSupport} 同构），避免各 Service 重复实现。</p>
 */
abstract class TaskServiceSupport {

    /** 分页参数换算结果。 */
    protected record Page(int pageNum, int pageSize, int offset) {
    }

    protected Page pageOf(PageQuery query) {
        PageQuery effective = query == null ? new PageQuery(null, null, null, null) : query;
        int pageNum = effective.normalizedPageNum();
        int pageSize = effective.normalizedPageSize();
        return new Page(pageNum, pageSize, (pageNum - 1) * pageSize);
    }

    protected String required(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new com.teamflow.common.exception.BusinessException(message);
        }
        return trimmed;
    }

    /** 当前登录账号（无上下文时用 system 兜底，写审计字段用）。 */
    protected String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : String.valueOf(authentication.getPrincipal());
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

    protected Integer numberAsInteger(Object value) {
        Long number = numberAsLong(value);
        return number == null ? null : number.intValue();
    }

    /** 解析 yyyy-MM-dd；空值返回 null。 */
    protected LocalDate parseDate(String value, String label) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ex) {
            throw new com.teamflow.common.exception.BusinessException(label + "格式应为 yyyy-MM-dd");
        }
    }

    /** 把数据库行里的 Date（java.sql.Date）安全转成字符串，便于统一响应。 */
    protected String dateText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().toString();
        }
        if (value instanceof LocalDate localDate) {
            return localDate.toString();
        }
        String text = String.valueOf(value);
        return text.length() > 10 ? text.substring(0, 10) : text;
    }
}
