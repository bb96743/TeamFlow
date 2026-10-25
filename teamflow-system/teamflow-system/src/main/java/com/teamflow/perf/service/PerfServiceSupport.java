package com.teamflow.perf.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * M05 绩效服务层公共支撑：分页换算、请求值归一化、操作人解析、数值口径工具。
 *
 * <p>仅承载与业务无关的样板逻辑，避免各 Service 重复实现；口径常量集中在此以便评审。</p>
 */
abstract class PerfServiceSupport {

    /** 0–100 的百分比刻度。 */
    protected static final BigDecimal HUNDRED = new BigDecimal("100");
    /** D-06.3：人工校准相对自动值的双向限幅（个百分点）。 */
    protected static final BigDecimal CALIBRATION_LIMIT = new BigDecimal("20");

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
        return value == null ? 0 : numberAsLong(value).intValue();
    }

    protected BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    protected BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 百分比分值，constrain 到 [0,100] 并保留 2 位小数。 */
    protected BigDecimal percent(BigDecimal value) {
        BigDecimal effective = zeroIfNull(value);
        if (effective.signum() < 0) {
            effective = BigDecimal.ZERO;
        }
        if (effective.compareTo(HUNDRED) > 0) {
            effective = HUNDRED;
        }
        return scale2(effective);
    }

    protected LocalDate parseDate(String value, String label) {
        String text = required(value, label + "不能为空");
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(label + "格式应为 yyyy-MM-dd");
        }
    }
}
