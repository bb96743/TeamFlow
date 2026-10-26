package com.teamflow.meeting.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.meeting.mapper.MeetingPlatformMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * M06 服务层公共支撑：分页换算、当前用户解析、时间解析与归一化。
 *
 * <p>M06 RULE R2：库内统一 DATETIME（服务器时区 +08:00），接口按 ISO-8601 传输；
 * 这里接受 ISO-8601 与常见空格格式，统一转成 {@code yyyy-MM-dd HH:mm:ss} 落库。</p>
 */
abstract class MeetingServiceSupport {

    protected static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    protected static final DateTimeFormatter DB_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter ISO_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter SPACE_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SPACE_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    protected final MeetingPlatformMapper platformMapper;

    protected MeetingServiceSupport(MeetingPlatformMapper platformMapper) {
        this.platformMapper = platformMapper;
    }

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

    protected Map<String, Object> currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String username = String.valueOf(authentication.getPrincipal());
        if (username.isBlank() || "anonymousUser".equals(username)) {
            return null;
        }
        return platformMapper.selectUserByUsername(username);
    }

    protected Long currentUserIdOrNull() {
        Map<String, Object> user = currentUserOrNull();
        return user == null ? null : numberAsLong(user.get("userId"));
    }

    protected Long requireCurrentUserId() {
        Long userId = currentUserIdOrNull();
        if (userId == null) {
            throw new BusinessException(401, "未认证或登录已过期");
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

    protected int intOf(Object value) {
        Long number = numberAsLong(value);
        return number == null ? 0 : number.intValue();
    }

    protected String digit(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 解析接口时间参数：接受 ISO-8601（T 分隔）与空格分隔，可带或不带秒。 */
    protected LocalDateTime parseDateTime(String value, String label) {
        String text = required(value, label + "不能为空");
        for (DateTimeFormatter formatter : new DateTimeFormatter[]{ISO_SECONDS, ISO_MINUTES, SPACE_SECONDS, SPACE_MINUTES}) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        throw new BusinessException(label + "格式应为 ISO-8601（如 2026-10-27T14:00:00）");
    }

    /** 解析库内时间字符串（yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd HH:mm）。 */
    protected LocalDateTime parseDbDateTime(Object value, String label) {
        if (value == null) {
            throw new BusinessException(label + "为空");
        }
        String text = String.valueOf(value);
        for (DateTimeFormatter formatter : new DateTimeFormatter[]{SPACE_SECONDS, SPACE_MINUTES}) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        throw new BusinessException(label + "格式无法解析：" + text);
    }

    protected String dbDateTime(LocalDateTime time) {
        return time.format(DB_FORMAT);
    }

    protected String displayDateTime(LocalDateTime time) {
        return time.format(DISPLAY_FORMAT);
    }

    protected void validateTimeRange(LocalDateTime start, LocalDateTime end) {
        if (!end.isAfter(start)) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }
        if (start.plusDays(7).isBefore(end)) {
            throw new BusinessException("单次会议/日程时长不能超过 7 天");
        }
    }
}
