package com.teamflow.okr.domain.dto;

import java.math.BigDecimal;

/**
 * KR 进度更新请求（M02 RULE R1 + L0 §9.1 D-2026-10-25-03）。
 *
 * <p>量化型：给 currentValue 按公式重算（progress_source=quantitative）；
 * 直接给 progress 视为人工校准（progress_source=manual），必须同时给 reason。
 * 任务驱动型不允许人工填写，进度由关联任务完成度决定。</p>
 */
public class OkrKeyResultProgressRequest {

    private BigDecimal currentValue;
    private BigDecimal progress;
    private String reason;

    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal currentValue) { this.currentValue = currentValue; }
    public BigDecimal getProgress() { return progress; }
    public void setProgress(BigDecimal progress) { this.progress = progress; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
