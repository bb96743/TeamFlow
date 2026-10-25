package com.teamflow.perf.controller;

import com.teamflow.common.domain.R;
import com.teamflow.framework.audit.OperLog;
import com.teamflow.framework.security.RequiresPermission;
import com.teamflow.perf.domain.dto.PerfCollectRequest;
import com.teamflow.perf.service.PerfCollectService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M05 归集接口（{@code /api/perf/collect}）：归集预览（不落库）与重算（覆盖写、幂等）。
 *
 * <p>预览/重算都经 M02 / M03 的 Service 取数，返回结果内每项可归集指标携带下钻来源
 * （具体 KR 清单与任务清单、人工校准量），满足 DoD「可追溯」。</p>
 */
@RestController
@RequestMapping("/api/perf/collect")
public class PerfCollectController {

    private final PerfCollectService service;

    public PerfCollectController(PerfCollectService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    @RequiresPermission("perf:cycle:collect")
    public R<Map<String, Object>> preview(@RequestBody PerfCollectRequest request) {
        return R.success(service.preview(request));
    }

    @PostMapping("/recalculate")
    @RequiresPermission("perf:cycle:collect")
    @OperLog(module = "绩效归集", action = "重算")
    public R<Map<String, Object>> recalculate(@RequestBody PerfCollectRequest request) {
        return R.success(service.recalculate(request));
    }
}
