package com.teamflow.personal.controller;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.personal.domain.dto.TodoHandleRequest;
import com.teamflow.personal.domain.dto.TodoPushRequest;
import com.teamflow.personal.domain.vo.PersonalIdResponse;
import com.teamflow.personal.service.TodoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M10 待办收件箱接口（{@code /api/personal/todos}），需求 2.3.1。
 *
 * <p>横切约定：每个接口都有 {@code @RequiresPermission}（{@code personal:*}）；写接口加 {@code @OperLog}；
 * 数据范围按「当前登录用户本人」显式过滤（M10 数据全部是个人私有，不使用部门级 {@code @DataScope}）。</p>
 */
@RestController
@RequestMapping("/api/personal/todos")
public class TodoController {

    private final TodoService todoService;

    public TodoController(TodoService todoService) {
        this.todoService = todoService;
    }

    @GetMapping
    @RequiresPermission("personal:todo:list")
    public R<PageResult<Map<String, Object>>> list(
            PageQuery query,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Long bizId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String dueBefore
    ) {
        return R.success(todoService.list(query, bizType, bizId, status, priority, dueBefore));
    }

    /** bizType 集中登记表 + handler 注册情况（只读，便于前端判断能否直接在收件箱处理）。 */
    @GetMapping("/biz-types")
    @RequiresPermission("personal:todo:list")
    public R<Map<String, Object>> bizTypes() {
        return R.success(todoService.bizTypes());
    }

    /**
     * 处理待办（R3）：必须先由原模块 handler 回写业务状态，成功后才关闭；否则保持未处理并返回 400。
     */
    @PostMapping("/{id}/handle")
    @RequiresPermission("personal:todo:handle")
    @OperLog(module = "待办收件箱", action = "处理待办")
    public R<Map<String, Object>> handle(@PathVariable Long id, @RequestBody TodoHandleRequest request) {
        return R.success(todoService.handle(id, request));
    }

    /**
     * 统一投递入口（R1，内部鉴权）：业务模块登记待办。M03 在进程内复用同一个服务方法，
     * 本接口面向后续跨进程/外部模块，权限码 {@code personal:todo:push} 不授予一般角色。
     */
    @PostMapping("/internal/push")
    @RequiresPermission("personal:todo:push")
    @OperLog(module = "待办收件箱", action = "内部投递")
    public R<PersonalIdResponse> push(@RequestBody TodoPushRequest request) {
        return R.success(new PersonalIdResponse(todoService.push(request)));
    }
}
