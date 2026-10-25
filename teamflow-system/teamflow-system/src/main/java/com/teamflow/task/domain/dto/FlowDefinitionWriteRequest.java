package com.teamflow.task.domain.dto;

/**
 * 流程定义新增 / 编辑请求（{@code /api/task/flow/definitions}）。
 *
 * <p>本期流程定义为「线性节点清单」，用 {@code nodesJson} 存节点数组；不做 BPMN 编排
 * （D-2026-10-25-01，复杂审批属 P2 的 3.1 审批中心）。</p>
 */
public class FlowDefinitionWriteRequest {

    private String defCode;
    private String defName;
    private String businessType;
    private String nodesJson;
    private Integer status;
    private String remark;

    public String getDefCode() { return defCode; }
    public void setDefCode(String defCode) { this.defCode = defCode; }
    public String getDefName() { return defName; }
    public void setDefName(String defName) { this.defName = defName; }
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }
    public String getNodesJson() { return nodesJson; }
    public void setNodesJson(String nodesJson) { this.nodesJson = nodesJson; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
