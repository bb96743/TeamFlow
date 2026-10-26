package com.teamflow.task.service;

import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.task.mapper.TaskMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * M03 **按 KR 的任务统计端口**（L0 §9.1 {@code D-2026-10-26-09} 结论二）：供其它模块（如 M14 里程碑完成度）
 * 读取「某 KR 下的顶层任务完成情况」的**聚合数值**。
 *
 * <p><b>为什么需要它</b>：里程碑完成度必须与「谁在看」无关；若消费方用 {@code TaskService#list} 取数，
 * 会叠加登录用户的数据范围，导致受限账号看到的完成度偏低、不同人看到不同值。本端口刻意
 * **不叠加登录用户数据范围**，口径只由数据本身决定。</p>
 *
 * <p><b>安全边界（必须遵守）</b>：本端口只返回聚合数值
 * （{@code krId / taskTotal / taskDone / completionPercent}），**不返回任务标题、负责人、描述等任何明细**。
 * 调用方在调用前**必须保证 krId 来自其有权访问的业务对象**（例如 M14 的里程碑自身已按项目数据范围校验过）；
 * 本端口不做业务对象授权判断。</p>
 *
 * <p><b>口径</b>：只统计**顶层任务**（{@code parentTaskId} 为空的根任务，子任务不计入分母，与 D-03 口径一致）；
 * {@code completionPercent = taskDone / taskTotal × 100}（保留 2 位小数）；该 KR 无顶层任务时
 * {@code taskTotal=0 / taskDone=0 / completionPercent=null}（不臆造 0%）。</p>
 */
@Service
public class TaskStatsQueryService {

    /** 分页读取任务的页大小（仅用于内部计数，不对外暴露明细）。 */
    private static final int PAGE_SIZE = 200;
    /** 单个 KR 最多读取的页数（上限约 2 万任务；超过则按已读取部分统计并在 Javadoc 说明）。 */
    private static final int MAX_PAGES = 100;

    private final TaskMapper taskMapper;

    public TaskStatsQueryService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 批量统计 KR 下的顶层任务完成情况。
     *
     * @param krIds KR ID 集合（可含 null/重复，内部去重；空集合返回空列表）
     * @return 每个 KR 一行：{@code krId / taskTotal / taskDone / completionPercent}；
     *         无任务的 KR 也会返回（{@code taskTotal=0, completionPercent=null}），便于消费方补齐
     */
    public List<Map<String, Object>> taskStatsByKrIds(Collection<Long> krIds) {
        if (krIds == null || krIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = krIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> stats = new ArrayList<>(ids.size());
        for (Long krId : ids) {
            stats.add(oneKrStats(krId));
        }
        return stats;
    }

    private Map<String, Object> oneKrStats(Long krId) {
        long taskTotal = 0;
        long taskDone = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Map<String, Object>> rows = taskMapper.selectTasks(
                    null, null, null, krId, null, false, DataScopeCriteria.all(),
                    page * PAGE_SIZE, PAGE_SIZE);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> task : rows) {
                if (task.get("parentTaskId") != null) {
                    continue;
                }
                taskTotal++;
                if ("done".equals(String.valueOf(task.get("status")))) {
                    taskDone++;
                }
            }
            if (rows.size() < PAGE_SIZE) {
                break;
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("krId", krId);
        row.put("taskTotal", taskTotal);
        row.put("taskDone", taskDone);
        row.put("completionPercent", taskTotal == 0 ? null
                : BigDecimal.valueOf(taskDone).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(taskTotal), 2, RoundingMode.HALF_UP));
        return row;
    }
}
