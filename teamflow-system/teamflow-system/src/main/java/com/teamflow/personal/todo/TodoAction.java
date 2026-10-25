package com.teamflow.personal.todo;

import com.teamflow.common.exception.BusinessException;

/**
 * 收件箱处理动作（L0 §9.1 {@code D-2026-10-26-07} 契约的一部分）。
 *
 * <p>统一入口接受的动作只有两种：{@link #COMPLETE}（在收件箱完成该事项）与 {@link #IGNORE}
 * （忽略/搁置）。业务模块的 {@link TodoBizHandler} 自行决定是否接受该动作：
 * <b>返回 false 或抛异常时，M10 不会关闭待办</b>（R3：不允许只关待办不改业务）。</p>
 */
public enum TodoAction {

    /** 完成：要求原模块把业务状态推进到「已处理」。 */
    COMPLETE,
    /** 忽略：允许业务模块拒绝（例如指派/审批必须回写业务状态，不允许静默忽略）。 */
    IGNORE;

    public static TodoAction of(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("处理动作不能为空，只能为 complete 或 ignore");
        }
        String normalized = value.trim().toLowerCase();
        for (TodoAction action : values()) {
            if (action.name().toLowerCase().equals(normalized)) {
                return action;
            }
        }
        throw new BusinessException("处理动作只能为 complete 或 ignore，收到：" + value);
    }

    public String code() {
        return name().toLowerCase();
    }
}
