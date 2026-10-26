package com.teamflow.ai.support;

import com.teamflow.common.exception.BusinessException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;

/**
 * M11 日期区间工具：规则实现里的「本周/上周/今天/本月」槽位统一在这里换算。
 */
public final class AiDates {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AiDates() {
    }

    public static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim().substring(0, 10), DATE);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(field + "格式应为 yyyy-MM-dd");
        }
    }

    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static LocalDate weekEnd(LocalDate date) {
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    public static LocalDate monthStart(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    public static LocalDate monthEnd(LocalDate date) {
        return date.with(TemporalAdjusters.lastDayOfMonth());
    }

    public static boolean inRange(String dateText, LocalDate from, LocalDate to) {
        if (dateText == null || dateText.isBlank()) {
            return false;
        }
        String normalized = dateText.length() > 10 ? dateText.substring(0, 10) : dateText;
        LocalDate date;
        try {
            date = LocalDate.parse(normalized, DATE);
        } catch (DateTimeParseException exception) {
            return false;
        }
        return !date.isBefore(from) && !date.isAfter(to);
    }

    public static String dateTime(LocalDate date) {
        return date.atStartOfDay().format(DATETIME);
    }

    public static String endOfDay(LocalDate date) {
        return date.atTime(23, 59, 59).format(DATETIME);
    }

    public static String display(LocalDateTime dateTime) {
        return dateTime == null ? "—" : dateTime.format(DATETIME);
    }

    public static String rangeLabel(LocalDate from, LocalDate to) {
        return DATE.format(from) + " ~ " + DATE.format(to);
    }
}
