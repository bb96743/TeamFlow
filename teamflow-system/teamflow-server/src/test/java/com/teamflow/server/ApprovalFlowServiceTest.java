package com.teamflow.server;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.task.service.ApprovalFlowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M03 通用审批服务测试（L0 §9.1 {@code D-2026-10-26-09} 结论一）。
 *
 * <p>覆盖：通用审批生命周期（发起 → 我的待办 → 按业务查实例 → 实例详情 → 审批 → 重复审批被拒）、
 * 输入校验（实例类型命名/定义存在且启用/审批人有效/审批人权限/审批结果取值）。
 * 直接调用 Service 层，验证「与任务解耦的通用审批出口」不依赖 {@code task_*} 表。</p>
 *
 * <p><b>测试自清理</b>：本用例只创建 {@code flow_instance}/{@code flow_task}/{@code flow_node_instance}
 * 三类流程引擎行（负 bizId，不与真实业务冲突），{@code @AfterEach} 按实例 ID 全部删除。</p>
 */
@SpringBootTest(classes = TeamFlowApplication.class)
class ApprovalFlowServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1_000L);

    @Autowired
    private ApprovalFlowService approvalFlowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdInstanceIds = new ArrayList<>();

    @BeforeEach
    void loginAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of()));
    }

    @AfterEach
    void cleanup() {
        for (Long instanceId : createdInstanceIds) {
            try {
                jdbcTemplate.update("DELETE FROM flow_node_instance WHERE instance_id = ?", instanceId);
                jdbcTemplate.update("DELETE FROM flow_task WHERE instance_id = ?", instanceId);
                jdbcTemplate.update("DELETE FROM flow_instance WHERE id = ?", instanceId);
            } catch (RuntimeException ignored) {
                // 清理失败不掩盖真实断言错误
            }
        }
        createdInstanceIds.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void genericApprovalLifecycle() {
        long bizId = -SEQ.incrementAndGet();
        Long instanceId = approvalFlowService.start(
                "test_approval", "task_approve_single", bizId, "单测业务标题", 1L);
        createdInstanceIds.add(instanceId);
        assertThat(instanceId).isNotNull();

        // 我的待办（审批人 1 = admin）
        List<Map<String, Object>> pending = approvalFlowService.myPending(1L);
        assertThat(pending).anySatisfy(row -> {
            assertThat(((Number) row.get("instanceId")).longValue()).isEqualTo(instanceId);
            assertThat(String.valueOf(row.get("taskTitle"))).contains("单测业务标题");
            assertThat(row).containsEntry("instanceType", "test_approval");
        });

        // 按业务查实例（不叠加用户数据范围）
        List<Map<String, Object>> byBiz = approvalFlowService.findByBiz("test_approval", bizId);
        assertThat(byBiz).hasSize(1);
        assertThat(byBiz.get(0)).containsEntry("instanceType", "test_approval")
                .containsEntry("status", "running")
                .containsEntry("bizId", bizId);

        // 实例详情：实例 + 节点轨迹 + 待办
        Map<String, Object> detail = approvalFlowService.instanceDetail(instanceId);
        assertThat(detail).containsKeys("instanceType", "status", "nodes", "flowTasks");
        assertThat(detail).containsEntry("instanceType", "test_approval").containsEntry("status", "running");

        // 审批通过
        Map<String, Object> result = approvalFlowService.decide(instanceId, "approve", "同意");
        assertThat(result).containsEntry("approved", true)
                .containsEntry("instanceStatus", "completed")
                .containsEntry("instanceType", "test_approval");
        assertThat(((Number) result.get("bizId")).longValue()).isEqualTo(bizId);

        // 重复审批被拒
        assertThatThrownBy(() -> approvalFlowService.decide(instanceId, "approve", "再批一次"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能重复审批");

        // 审批后状态与轨迹
        assertThat(approvalFlowService.findByBiz("test_approval", bizId).get(0)).containsEntry("status", "completed");
        assertThat((List<?>) approvalFlowService.instanceDetail(instanceId).get("nodes")).isNotEmpty();
    }

    @Test
    void invalidInputIsRejected() {
        long bizId = -SEQ.incrementAndGet();
        // 实例类型命名不合法（大写/超长）
        assertThatThrownBy(() -> approvalFlowService.start("BAD-TYPE", "task_approve_single", bizId, "x", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("实例类型不合法");
        // 流程定义不存在
        assertThatThrownBy(() -> approvalFlowService.start("test_approval", "def_not_exist", bizId, "x", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("流程定义不存在");
        // 审批人不存在
        assertThatThrownBy(() -> approvalFlowService.start("test_approval", "task_approve_single", bizId, "x", 999999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("审批人不存在");

        // 非指定审批人审批 → 无权审批；审批结果取值不合法 → 明确报错
        Long instanceId = approvalFlowService.start("test_approval", "task_approve_single", bizId, "权限校验", 2L);
        createdInstanceIds.add(instanceId);
        assertThatThrownBy(() -> approvalFlowService.decide(instanceId, "maybe", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("审批结果不合法");
        assertThatThrownBy(() -> approvalFlowService.decide(instanceId, "approve", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权审批");
    }
}
