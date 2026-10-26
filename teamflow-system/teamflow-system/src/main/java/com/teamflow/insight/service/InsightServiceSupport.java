package com.teamflow.insight.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.exception.BusinessException;
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
 * M13 统计服务层公共支撑：分页换算、日期与数值口径、指标读数载荷拼装。
 *
 * <p>所有统计都按 **服务器时区 +08:00** 聚合（M13 RULE R6）；数值统一保留 2 位小数、四舍五入。</p>
 */
abstract class InsightServiceSupport {

    protected static final BigDecimal HUNDRED = new BigDecimal("100");
    protected static final int MAX_RANGE_DAYS = 366;
    protected static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    protected static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

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

    protected int intOf(Object value) {
        Long number = numberAsLong(value);
        return number == null ? 0 : number.intValue();
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
        return numerator.multiply(HUNDRED).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    protected BigDecimal average(BigDecimal sum, long count) {
        if (count <= 0) {
            return null;
        }
        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    protected LocalDate parseDate(String value, String label) {
        String text = required(value, label + "不能为空");
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(label + "格式应为 yyyy-MM-dd");
        }
    }

    /** 统计区间校验：起止必填、结束不早于开始、跨度不超过 366 天（R5 防止一次扫全量）。 */
    protected void requireValidRange(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("统计结束日期不能早于开始日期");
        }
        long days = endDate.toEpochDay() - startDate.toEpochDay() + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new BusinessException("统计区间最长 " + MAX_RANGE_DAYS + " 天，请缩小范围（M13 R5：避免一次扫描全量）");
        }
    }

    protected LocalDateTime parseDateTime(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, DATE_TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    protected LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10), DATE_FORMAT);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * 指标读数载荷：把「字典口径」与「本次统计值」一起返回，前端可直接展示口径说明。
     *
     * @param metric  字典行（必须已上线）
     * @param value   统计值；明细型指标传 null
     * @param unit    单位（% / 小时 / 个 / null）
     * @param missing 是否无数据（R7：明确 null/0 的语义）
     * @param note    缺失/口径补充说明
     */
    protected Map<String, Object> metricPayload(Map<String, Object> metric, Object value, String unit,
                                                boolean missing, String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", metric.get("metricCode"));
        payload.put("name", metric.get("metricName"));
        payload.put("category", metric.get("metricCategory"));
        payload.put("definition", metric.get("metricDefinition"));
        payload.put("formula", metric.get("formula"));
        payload.put("sourceModule", metric.get("sourceModule"));
        payload.put("sourceDetail", metric.get("sourceDetail"));
        payload.put("timeGrain", metric.get("timeGrain"));
        payload.put("missingPolicy", metric.get("missingPolicy"));
        payload.put("refreshFrequency", metric.get("refreshFrequency"));
        payload.put("cacheTtlSeconds", metric.get("cacheTtlSeconds"));
        payload.put("ownerName", metric.get("ownerName"));
        payload.put("status", metric.get("status"));
        payload.put("value", value);
        payload.put("unit", unit);
        payload.put("missing", missing);
        payload.put("note", note);
        return payload;
    }

    protected String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "draft" -> "草稿";
            case "online" -> "已上线";
            case "offline" -> "已下线";
            default -> status;
        };
    }

    protected String categoryText(String category) {
        return switch (category == null ? "" : category) {
            case "task" -> "任务统计";
            case "team" -> "团队效能";
            case "cross" -> "跨域指标";
            default -> category;
        };
    }
}
