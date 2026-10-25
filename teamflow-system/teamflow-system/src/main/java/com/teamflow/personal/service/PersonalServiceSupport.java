package com.teamflow.personal.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.personal.mapper.PersonalPlatformMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * M10 服务层公共支撑：分页换算、当前用户解析、请求值归一化。
 *
 * <p>M10 的数据全部是「本人的」，因此不使用 {@code @DataScope}（数据范围按部门/本人放行是平台底座查询的语义），
 * 而是显式按 {@code user_id = 当前用户} 过滤，越权访问一律返回「不存在或无权访问」。</p>
 */
abstract class PersonalServiceSupport {

    /** 分页参数换算结果。 */
    protected record Page(int pageNum, int pageSize, int offset) {
    }

    protected final PersonalPlatformMapper platformMapper;
    protected PersonalServiceSupport(PersonalPlatformMapper platformMapper) {
        this.platformMapper = platformMapper;
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

    /** 当前登录用户 ID；解析不到直接拒绝（个人数据没有"匿名"语义）。 */
    protected Long currentUserId() {
        Long userId = platformMapper.selectUserIdByUsername(currentUsername());
        if (userId == null) {
            throw new BusinessException("当前登录用户不存在");
        }
        return userId;
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
            throw new BusinessException(label + "格式应为 yyyy-MM-dd");
        }
    }

    /**
     * 解析日期时间：支持 {@code yyyy-MM-dd HH:mm:ss}、{@code yyyy-MM-dd HH:mm} 与纯日期（按当天 23:59:59）。
     * 用于截止时间筛选与投递参数。
     */
    protected LocalDateTime parseDateTime(String value, String label) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            if (text.length() == 10) {
                return LocalDate.parse(text).atTime(23, 59, 59);
            }
            if (text.length() == 16) {
                return LocalDateTime.parse(text.replace(' ', 'T') + ":00");
            }
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (DateTimeParseException ex) {
            throw new BusinessException(label + "格式应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    /** 数据库行里的日期（java.sql.Date/LocalDate）转字符串。 */
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
