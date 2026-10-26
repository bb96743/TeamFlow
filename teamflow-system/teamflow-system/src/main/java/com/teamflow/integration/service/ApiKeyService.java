package com.teamflow.integration.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.security.PermissionResolver;
import com.teamflow.integration.domain.dto.ApiKeyCreateRequest;
import com.teamflow.integration.domain.vo.ApiKeyCreatedResponse;
import com.teamflow.integration.mapper.OpenApiKeyMapper;
import com.teamflow.integration.mapper.IntegrationPlatformMapper;
import com.teamflow.integration.security.ApiKeyIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 开放 API 密钥服务（2.4.3）：创建、撤销、列表与请求认证。
 *
 * <p><b>安全</b>：库中只存 SHA-256 哈希与前缀；明文只在创建响应返回一次。
 * Key 绑定用户与权限范围：调用时以绑定用户身份执行，并受 scope 约束；
 * 撤销、过期、关联应用停用、绑定用户停用都会立即失效。</p>
 */
@Service
public class ApiKeyService extends IntegrationSupport {

    private static final DateTimeFormatter EXPIRES_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OpenApiKeyMapper keyMapper;
    private final IntegrationPlatformMapper platformMapper;
    private final PermissionResolver permissionResolver;

    public ApiKeyService(
            OpenApiKeyMapper keyMapper,
            IntegrationPlatformMapper platformMapper,
            PermissionResolver permissionResolver
    ) {
        this.keyMapper = keyMapper;
        this.platformMapper = platformMapper;
        this.permissionResolver = permissionResolver;
    }

    /**
     * 请求认证：校验前缀/哈希/状态/过期/关联应用/绑定用户，成功返回身份并累加调用次数。
     *
     * <p>任何一项不满足都返回空（由过滤器统一 401），不泄露具体失败原因给未认证调用方。</p>
     */
    @Transactional
    public Optional<ApiKeyIdentity> authenticate(String plainKey) {
        String key = trimToNull(plainKey);
        if (key == null || !key.startsWith(API_KEY_PREFIX) || key.length() < 20) {
            return Optional.empty();
        }
        Map<String, Object> row = keyMapper.selectByHash(sha256Hex(key));
        if (row == null) {
            return Optional.empty();
        }
        if (!"active".equals(stringOf(row.get("status")))) {
            return Optional.empty();
        }
        if (!booleanOf(row.get("notExpired"))) {
            return Optional.empty();
        }
        Long appId = numberAsLong(row.get("appId"));
        if (appId != null && !"enabled".equals(stringOf(row.get("appStatus")))) {
            return Optional.empty();
        }
        Long userId = numberAsLong(row.get("userId"));
        if (userId == null) {
            return Optional.empty();
        }
        String username = platformMapper.selectUsernameById(userId);
        if (username == null || platformMapper.countActiveUser(userId) == 0) {
            return Optional.empty();
        }
        Long keyId = numberAsLong(row.get("id"));
        keyMapper.touchUsage(keyId);
        return Optional.of(new ApiKeyIdentity(keyId, appId, userId, username,
                stringOf(row.get("keyPrefix")), ApiKeyIdentity.parseScope(stringOf(row.get("scope")))));
    }

    @Transactional
    public ApiKeyCreatedResponse create(ApiKeyCreateRequest request) {
        String keyName = required(request == null ? null : request.keyName(), "密钥名称不能为空（写清用途，便于审计）");
        Long userId = request.userId();
        if (userId == null) {
            userId = platformMapper.selectUserIdByUsername(currentUsername());
        }
        if (userId == null || platformMapper.countActiveUser(userId) == 0) {
            throw new BusinessException("绑定用户不存在或已停用");
        }
        String username = platformMapper.selectUsernameById(userId);
        if (username == null) {
            throw new BusinessException("绑定用户不存在");
        }
        Long appId = request.appId();
        Set<String> scope = normalizeScope(request.scope(), username);
        String expiresAt = trimToNull(request.expiresAt());
        if (expiresAt != null) {
            try {
                LocalDateTime expires = LocalDateTime.parse(expiresAt, EXPIRES_FORMAT);
                if (expires.isBefore(LocalDateTime.now())) {
                    throw new BusinessException("过期时间必须晚于当前时间");
                }
            } catch (DateTimeParseException exception) {
                throw new BusinessException("过期时间格式应为 yyyy-MM-dd HH:mm");
            }
        }
        String plainKey = generateApiKey();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("appId", appId);
        row.put("keyName", keyName);
        row.put("keyPrefix", keyPrefixOf(plainKey));
        row.put("keyHash", sha256Hex(plainKey));
        row.put("userId", userId);
        row.put("scope", scope.isEmpty() ? null : String.join(",", scope));
        row.put("expiresAt", expiresAt == null ? null : LocalDateTime.parse(expiresAt, EXPIRES_FORMAT));
        row.put("remark", trimToNull(request.remark()));
        keyMapper.insertKey(row, currentUsername());
        Long keyId = numberAsLong(row.get("id"));
        return new ApiKeyCreatedResponse(keyId, keyName, stringOf(row.get("keyPrefix")), plainKey, userId,
                stringOf(row.get("scope")), expiresAt,
                "密钥明文只显示这一次，请立即保存；库中只保存哈希与前缀，遗失只能撤销后重建。");
    }

    @Transactional
    public void revoke(Long id) {
        Map<String, Object> row = keyMapper.selectById(id);
        if (row == null) {
            throw new BusinessException("API Key 不存在");
        }
        if (!"active".equals(stringOf(row.get("status")))) {
            throw new BusinessException("该密钥已撤销，无需重复操作");
        }
        keyMapper.revoke(id, currentUsername());
    }

    public PageResult<Map<String, Object>> list(PageQuery query, String keyword, Long appId, String status) {
        Page page = pageOf(query);
        String statusFilter = trimToNull(status);
        long total = keyMapper.countKeys(trimToNull(keyword), appId, statusFilter);
        List<Map<String, Object>> rows = keyMapper.selectKeys(trimToNull(keyword), appId, statusFilter,
                page.offset(), page.pageSize());
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = keyMapper.selectById(id);
        if (row == null) {
            throw new BusinessException("API Key 不存在");
        }
        return row;
    }

    private Set<String> normalizeScope(String rawScope, String username) {
        Set<String> scope = new LinkedHashSet<>();
        String trimmed = trimToNull(rawScope);
        if (trimmed == null) {
            return scope;
        }
        for (String item : trimmed.split(",")) {
            String code = item.trim();
            if (code.isEmpty()) {
                continue;
            }
            if (!permissionResolver.hasPermission(username, code)) {
                throw new BusinessException("权限范围包含绑定用户不具备的权限：" + code);
            }
            scope.add(code);
        }
        return scope;
    }
}
