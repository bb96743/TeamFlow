package com.teamflow.knowledge.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.knowledge.mapper.KnowledgePlatformMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M07 服务层公共支撑：分页换算、当前用户解析、请求值归一化。
 *
 * <p>只承载与具体业务无关的样板逻辑；权限链计算在
 * {@link KnowledgeAccessService}，跨模块契约在归档/正文切片 Service。</p>
 */
abstract class KnowledgeServiceSupport {

    protected record Page(int pageNum, int pageSize, int offset) {
    }

    protected final KnowledgePlatformMapper platformMapper;

    protected KnowledgeServiceSupport(KnowledgePlatformMapper platformMapper) {
        this.platformMapper = platformMapper;
    }

    protected Page pageOf(PageQuery query) {
        PageQuery effective = query == null ? new PageQuery(null, null, null, null) : query;
        int pageNum = effective.normalizedPageNum();
        int pageSize = effective.normalizedPageSize();
        return new Page(pageNum, pageSize, (pageNum - 1) * pageSize);
    }

    /** 当前登录账号；无安全上下文时返回 system（仅内部服务调用会出现）。 */
    protected String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : String.valueOf(authentication.getPrincipal());
    }

    /** 当前登录用户；未认证或账号不存在时返回 null（归档等内部调用可能无安全上下文）。 */
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

    /** 标签列表 → 逗号分隔标签串（去空、去重、保持顺序）。 */
    protected String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String tag : tags) {
            String trimmed = trimToNull(tag);
            if (trimmed != null) {
                unique.add(trimmed);
            }
        }
        return unique.isEmpty() ? null : String.join(",", unique);
    }
}
