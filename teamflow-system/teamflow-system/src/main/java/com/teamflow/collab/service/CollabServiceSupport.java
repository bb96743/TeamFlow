package com.teamflow.collab.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.collab.mapper.CollabPlatformMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M09 服务层公共支撑：分页换算、当前用户解析、请求值归一化、@提及解析。
 *
 * <p>只承载与具体业务无关的样板逻辑；通知/动态流等跨模块约束在各自 Service 内实现。</p>
 */
abstract class CollabServiceSupport {

    /** 正文中的 @账号 记号（账号由字母/数字/下划线组成）。 */
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_]{2,32})");

    protected record Page(int pageNum, int pageSize, int offset) {
    }

    protected final CollabPlatformMapper platformMapper;

    protected CollabServiceSupport(CollabPlatformMapper platformMapper) {
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

    protected Long requiredLong(Object value, String message) {
        Long number = numberAsLong(value);
        if (number == null) {
            throw new BusinessException(message);
        }
        return number;
    }

    /** 正文里的 @账号 提取。 */
    protected Set<String> extractMentionUsernames(String content) {
        Set<String> usernames = new LinkedHashSet<>();
        if (content == null) {
            return usernames;
        }
        Matcher matcher = MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            usernames.add(matcher.group(1));
        }
        return usernames;
    }

    /** 把账号解析为用户 ID（找不到的账号忽略，不阻断评论/消息保存）。 */
    protected List<Long> resolveMentionUserIds(String content, List<Long> explicitUserIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (explicitUserIds != null) {
            explicitUserIds.stream().filter(java.util.Objects::nonNull).forEach(ids::add);
        }
        Set<String> usernames = extractMentionUsernames(content);
        if (!usernames.isEmpty()) {
            for (Map<String, Object> row : platformMapper.selectUsersByUsernames(new ArrayList<>(usernames))) {
                Long userId = numberAsLong(row.get("userId"));
                if (userId != null) {
                    ids.add(userId);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /** 拼接逗号分隔的用户 ID（评论/消息表的提及字段）。 */
    protected String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
    }

    protected List<Long> splitIds(String text) {
        List<Long> ids = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return ids;
        }
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    ids.add(Long.valueOf(trimmed));
                } catch (NumberFormatException ignored) {
                    // 历史脏数据忽略
                }
            }
        }
        return ids;
    }

    /** 昵称翻译：批量取用户资料，返回 userId → 展示名（昵称优先、账号兜底）。 */
    protected Map<Long, String> userNames(List<Long> userIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        List<Long> distinct = userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return names;
        }
        for (Map<String, Object> brief : platformMapper.selectUserBriefs(distinct)) {
            Long userId = numberAsLong(brief.get("userId"));
            Object nickname = brief.get("nickname");
            Object username = brief.get("username");
            names.put(userId, nickname == null ? String.valueOf(username) : String.valueOf(nickname));
        }
        return names;
    }

    /** 任务状态展示文案（仅用于动态流摘要；状态判定始终以事件字段为准，不复制状态机）。 */
    protected String taskStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "pool" -> "待认领";
            case "todo" -> "待办";
            case "in_progress" -> "进行中";
            case "done" -> "已完成";
            case "closed" -> "已关闭";
            default -> status == null ? "" : status;
        };
    }
}
