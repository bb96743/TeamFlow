package com.teamflow.recruit.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.recruit.mapper.RecruitPlatformMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M08 招聘服务层公共支撑：分页换算、日期解析、状态机异常转业务异常、联系方式脱敏、编码生成。
 */
abstract class RecruitServiceSupport {

    protected static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    protected static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    protected final RecruitPlatformMapper platformMapper;

    protected RecruitServiceSupport(RecruitPlatformMapper platformMapper) {
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

    protected long longOf(Object value) {
        Long number = numberAsLong(value);
        return number == null ? 0L : number;
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

    protected LocalDateTime parseDateTime(String value, String label) {
        String text = required(value, label + "不能为空");
        try {
            return LocalDateTime.parse(text, DATE_TIME_FORMAT);
        } catch (DateTimeParseException first) {
            try {
                return LocalDate.parse(text, DATE_FORMAT).atStartOfDay();
            } catch (DateTimeParseException second) {
                throw new BusinessException(label + "格式应为 yyyy-MM-dd HH:mm 或 yyyy-MM-dd");
            }
        }
    }

    protected LocalDate parseDate(String value, String label) {
        if (trimToNull(value) == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DATE_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(label + "格式应为 yyyy-MM-dd");
        }
    }

    protected String dateText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 手机号脱敏：保留前 3 后 4（R6：列表默认脱敏）。 */
    protected String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null || phone.isEmpty() ? phone : "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 邮箱脱敏：保留首字符与域名（R6）。 */
    protected String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    protected String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9+]", "");
        return digits.isEmpty() ? null : digits;
    }

    protected String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim().toLowerCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 状态机异常（IllegalArgumentException）统一转业务异常，保证接口返回 400 且文案可读。 */
    protected void requireTransition(Runnable transitionCheck) {
        try {
            transitionCheck.run();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(exception.getMessage());
        }
    }

    /** 宽容解析数据库读出的时间值：LocalDateTime 原样返回；字符串兼容 yyyy-MM-dd HH:mm 与 ISO 两种格式。 */
    protected LocalDateTime parseDateTimeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        for (String pattern : new String[]{"yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss"}) {
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        try {
            return LocalDate.parse(text.substring(0, Math.min(10, text.length())), DATE_FORMAT).atStartOfDay();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    protected Map<String, Object> metaOf(Page page, long total) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("pageNum", page.pageNum());
        meta.put("pageSize", page.pageSize());
        meta.put("total", total);
        return meta;
    }
}
