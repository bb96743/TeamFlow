package com.teamflow.ai.tool;

import com.teamflow.ai.mapper.AiPlatformMapper;
import com.teamflow.ai.support.AiDates;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具公共能力：姓名槽位解析、时间区间换算、行字段取值。
 *
 * <p>员工姓名只用于「查询目标」定位，最终能否读到数据仍由业务 Service 的权限与数据范围决定。</p>
 */
@Component
public class AiToolSupport {

    private final AiPlatformMapper platformMapper;

    public AiToolSupport(AiPlatformMapper platformMapper) {
        this.platformMapper = platformMapper;
    }

    /** 按姓名/账号在员工目录中定位；先精确匹配，再包含匹配（取最长包含）。 */
    public Map<String, Object> findUser(String nameOrUsername) {
        if (nameOrUsername == null || nameOrUsername.isBlank()) {
            return null;
        }
        String target = nameOrUsername.trim();
        List<Map<String, Object>> users = platformMapper.selectUserDirectory();
        for (Map<String, Object> user : users) {
            if (target.equals(text(user, "nickname")) || target.equals(text(user, "username"))) {
                return user;
            }
        }
        Map<String, Object> matched = null;
        int matchedLength = 0;
        for (Map<String, Object> user : users) {
            String nickname = text(user, "nickname");
            String username = text(user, "username");
            if (!nickname.isBlank() && target.contains(nickname) && nickname.length() > matchedLength) {
                matched = user;
                matchedLength = nickname.length();
            } else if (!username.isBlank() && target.contains(username) && username.length() > matchedLength) {
                matched = user;
                matchedLength = username.length();
            }
        }
        return matched;
    }

    public String displayName(Map<String, Object> user) {
        if (user == null) {
            return "";
        }
        String nickname = text(user, "nickname");
        return nickname.isBlank() ? text(user, "username") : nickname;
    }

    public Long userId(Map<String, Object> user) {
        if (user == null || user.get("userId") == null) {
            return null;
        }
        Object value = user.get("userId");
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }

    /** 区间：this_week（默认）/ last_week / today / this_month。 */
    public LocalDate[] range(String range, LocalDate today) {
        String value = range == null || range.isBlank() ? "this_week" : range.trim();
        return switch (value) {
            case "today" -> new LocalDate[]{today, today};
            case "last_week" -> new LocalDate[]{
                    AiDates.weekStart(today).minusWeeks(1), AiDates.weekEnd(today).minusWeeks(1)};
            case "this_month" -> new LocalDate[]{AiDates.monthStart(today), AiDates.monthEnd(today)};
            default -> new LocalDate[]{AiDates.weekStart(today), AiDates.weekEnd(today)};
        };
    }

    public String rangeLabel(LocalDate[] range) {
        return AiDates.rangeLabel(range[0], range[1]);
    }

    public String text(Map<String, Object> row, String key) {
        if (row == null || row.get(key) == null) {
            return "";
        }
        return String.valueOf(row.get(key));
    }

    public String text(Map<String, Object> params, String key, String fallback) {
        if (params == null || params.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(params.get(key));
        return value.isBlank() ? fallback : value;
    }

    public Long longValue(Map<String, Object> params, String key) {
        if (params == null || params.get(key) == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(params.get(key)));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public String taskStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "todo" -> "待处理";
            case "in_progress" -> "进行中";
            case "done" -> "已完成";
            case "pool" -> "待认领";
            case "cancelled" -> "已取消";
            default -> status == null || status.isBlank() ? "未知" : status;
        };
    }

    public String perfStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "draft" -> "草稿";
            case "archived" -> "已归档";
            case "locked" -> "已锁定";
            default -> status == null || status.isBlank() ? "未知" : status;
        };
    }

    public List<Map<String, Object>> rowsOf(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) map;
                    rows.add(row);
                }
            }
            return rows;
        }
        return List.of();
    }
}
