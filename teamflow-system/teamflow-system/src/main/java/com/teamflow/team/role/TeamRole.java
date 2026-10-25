package com.teamflow.team.role;

import com.teamflow.common.exception.BusinessException;

/**
 * 团队内角色（M04 RULE R2，L0 §9.1 {@code D-2026-10-26-08} 结论二）。
 *
 * <p><b>语义边界</b>：团队角色只表达「团队内部分工」，用于团队内操作校验（如仅 {@link #OWNER} 可解散团队）
 * 与展示；**绝不写入 {@code sys_user_role}、绝不映射为系统权限码**。系统里"能不能看/能不能改"由
 * {@code team:*} 权限码决定，与 team_role 完全正交。</p>
 */
public enum TeamRole {

    /** 负责人：团队内最高分工角色（可解散团队、主持分工）。 */
    OWNER,
    /** 普通成员。 */
    MEMBER,
    /** 观察者：只读参与。 */
    VIEWER;

    /** 默认角色（加入团队未指定时）。 */
    public static final TeamRole DEFAULT = MEMBER;

    public static TeamRole of(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String normalized = value.trim().toUpperCase();
        for (TeamRole role : values()) {
            if (role.name().equals(normalized)) {
                return role;
            }
        }
        throw new BusinessException("团队角色只能为 OWNER、MEMBER 或 VIEWER，收到：" + value);
    }

    /** 团队角色中文标签（仅展示用）。 */
    public String label() {
        return switch (this) {
            case OWNER -> "负责人";
            case MEMBER -> "成员";
            case VIEWER -> "观察者";
        };
    }
}
