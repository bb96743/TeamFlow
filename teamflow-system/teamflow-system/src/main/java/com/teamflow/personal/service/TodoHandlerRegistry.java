package com.teamflow.personal.service;

import com.teamflow.personal.todo.TodoBizHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 待办回写扩展点注册表（L0 §9.1 {@code D-2026-10-26-07}）。
 *
 * <p><b>启动期校验</b>：Spring 注入所有 {@link TodoBizHandler} Bean，本类按 {@code bizType()} 建索引；
 * 空 bizType 或重复 bizType 直接让应用启动失败（fail-fast），避免运行时路由歧义。
 * M10 只依赖 {@link TodoBizHandler} 接口，**不 import 任何业务模块实现**。</p>
 *
 * <p><b>未登记 handler</b>：投递允许入库，处理时拒绝并提示「该类型暂不支持在收件箱直接处理」。因此
 * {@link #find(String)} 返回空不代表数据有问题，而是该 bizType 目前仅供展示/跳转。</p>
 */
@Component
public class TodoHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(TodoHandlerRegistry.class);

    private final Map<String, TodoBizHandler> handlers;

    public TodoHandlerRegistry(List<TodoBizHandler> beans) {
        Map<String, TodoBizHandler> byBizType = new LinkedHashMap<>();
        for (TodoBizHandler bean : beans) {
            String bizType = bean.bizType();
            if (bizType == null || bizType.isBlank()) {
                throw new IllegalStateException("TodoBizHandler 的 bizType 不能为空：" + bean.getClass().getName());
            }
            TodoBizHandler existing = byBizType.putIfAbsent(bizType, bean);
            if (existing != null) {
                throw new IllegalStateException("TodoBizHandler bizType 重复注册（必须唯一）：" + bizType
                        + " -> " + existing.getClass().getName() + " / " + bean.getClass().getName());
            }
        }
        this.handlers = Collections.unmodifiableMap(byBizType);
        log.info("待办回写扩展点注册完成：{} 个（{}）", handlers.size(), handlers.keySet());
    }

    public Optional<TodoBizHandler> find(String bizType) {
        return bizType == null ? Optional.empty() : Optional.ofNullable(handlers.get(bizType));
    }

    public boolean hasHandler(String bizType) {
        return find(bizType).isPresent();
    }

    public Set<String> registeredBizTypes() {
        return handlers.keySet();
    }

    /** 已注册的 bizType → 实现类简名（测试与排障用）。 */
    public Map<String, String> registered() {
        Map<String, String> result = new LinkedHashMap<>();
        handlers.forEach((bizType, handler) -> result.put(bizType, handler.getClass().getSimpleName()));
        return result;
    }
}
