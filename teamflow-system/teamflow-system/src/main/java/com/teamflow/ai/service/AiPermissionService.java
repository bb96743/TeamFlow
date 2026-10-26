package com.teamflow.ai.service;

import com.teamflow.ai.domain.AiCaller;
import com.teamflow.framework.security.PermissionResolver;
import com.teamflow.system.domain.vo.CurrentUserResponse;
import com.teamflow.system.service.AuthService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * M11 身份与权限入口。
 *
 * <p>工具执行身份只来自当前登录用户，<b>不提供任何系统超管身份</b>；权限码统一经框架的
 * {@link PermissionResolver} 校验，数据范围仍由业务 Service 的 {@code @DataScope} 计算。</p>
 */
@Service
public class AiPermissionService {

    private final AuthService authService;
    private final PermissionResolver permissionResolver;

    public AiPermissionService(AuthService authService, PermissionResolver permissionResolver) {
        this.authService = authService;
        this.permissionResolver = permissionResolver;
    }

    public AiCaller caller() {
        CurrentUserResponse user = authService.currentUser();
        return new AiCaller(
                user.userId(),
                user.username(),
                user.nickname(),
                user.deptId(),
                user.deptName(),
                user.roles(),
                user.permissions()
        );
    }

    public boolean has(AiCaller caller, String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return true;
        }
        return permissionResolver.hasPermission(caller.username(), permissionCode);
    }

    public void require(AiCaller caller, String permissionCode) {
        if (!has(caller, permissionCode)) {
            throw new AccessDeniedException("缺少权限：" + permissionCode);
        }
    }
}
