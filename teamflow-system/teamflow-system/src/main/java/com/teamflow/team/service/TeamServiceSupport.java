package com.teamflow.team.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.team.mapper.TeamPlatformMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * M04 服务层公共支撑：分页换算、当前用户解析、请求值归一化。
 *
 * <p>与 Okr/Task/Perf/Personal 的 Support 同构；团队角色相关的语义校验放在
 * {@link TeamMemberService} 与 {@link com.teamflow.team.role.TeamRole}，此处不承载业务规则。</p>
 */
abstract class TeamServiceSupport {

    /** 分页参数换算结果。 */
    protected record Page(int pageNum, int pageSize, int offset) {
    }

    protected final TeamPlatformMapper platformMapper;

    protected TeamServiceSupport(TeamPlatformMapper platformMapper) {
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

    /** 当前登录用户 ID；解析不到直接拒绝（团队操作必须绑定具体操作人）。 */
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

    /** 团队状态中文标签（仅展示用）。 */
    protected String teamStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "active" -> "正常";
            case "archived" -> "已归档";
            case "dissolved" -> "已解散";
            default -> status;
        };
    }
}
