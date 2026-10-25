package com.teamflow.perf.domain.dto;

import java.util.List;

/**
 * 自评提交请求：被评人恒为当前登录用户（R1/R4）。
 */
public class PerfSelfScoreRequest {

    private Long cycleId;
    private List<PerfItemScoreRequest> items;

    public Long getCycleId() { return cycleId; }
    public void setCycleId(Long cycleId) { this.cycleId = cycleId; }
    public List<PerfItemScoreRequest> getItems() { return items; }
    public void setItems(List<PerfItemScoreRequest> items) { this.items = items; }
}
