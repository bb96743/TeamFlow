package com.teamflow.perf.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.perf.metric.PerfMetricDictionary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M05 指标字典接口（{@code /api/perf/metrics}）。
 *
 * <p>R2 要求每个可归集指标登记四要素（数据来源模块 / 计算公式 / 取数时间范围 / 缺失值处理）；
 * 本接口把登记内容对外公开，评审者可直接核对口径，未登记指标不得被模板引用上线。</p>
 */
@RestController
@RequestMapping("/api/perf/metrics")
public class PerfMetricController {

    @GetMapping
    @RequiresPermission("perf:metric:list")
    public R<List<Map<String, Object>>> list() {
        return R.success(PerfMetricDictionary.definition());
    }
}
