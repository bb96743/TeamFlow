package com.teamflow.personal.todo;

/**
 * 待办回写原模块的**扩展点接口**（L0 §9.1 {@code D-2026-10-26-07}）。
 *
 * <p><b>为什么是接口</b>：M10 RULE R3 要求「收件箱点完成必须回写原模块」，但 L0 §4.3 又禁止跨模块直连业务表。
 * 若 M10 硬编码调用 M03/M05 的 Service，M10 会变成依赖中心（每新增一个投递方都要改 M10）。
 * 因此把回写动作反转成扩展点：各业务模块**自行实现本接口并注册为 Spring Bean**，M10 启动时收集
 * {@code List<TodoBizHandler>} 并按 {@link #bizType()} 建索引、校验唯一性；M10 只依赖本接口，
 * <b>不 import 任何业务模块实现</b>。</p>
 *
 * <p><b>返回契约</b>：</p>
 * <ul>
 *   <li>返回 {@code true}：原模块状态已变更（或已处于目标状态、本次无需再改），M10 关闭待办；</li>
 *   <li>返回 {@code false}：原模块**拒绝**本次处理（如任务已被删除、状态不允许），M10 保持待办未处理；</li>
 *   <li>抛异常：等同拒绝，且 M10 会连同本次处理一起回滚，待办保持未处理（R3 强约束）。</li>
 * </ul>
 *
 * <p><b>实现约束</b>：实现方必须保证「返回 false 时不要产生业务副作用」；返回 true 前必须已完成业务写入。
 * 同一 bizType 只允许一个实现（重复注册会让应用启动失败，避免路由歧义）。</p>
 */
public interface TodoBizHandler {

    /**
     * 本 handler 负责的业务类型（集中登记见 {@link TodoBizTypes}）。
     */
    String bizType();

    /**
     * 把收件箱动作回写到原模块。
     *
     * @param bizId  业务单据 ID（投递时由投递方给出；本模块原样回传给 handler）
     * @param userId 待办所属用户（M10 已校验为当前登录用户）
     * @param action 用户在收件箱选择的动作
     * @return true 表示可以关闭待办；false 表示原模块拒绝、待办保持未处理
     */
    boolean handle(Long bizId, Long userId, TodoAction action);
}
