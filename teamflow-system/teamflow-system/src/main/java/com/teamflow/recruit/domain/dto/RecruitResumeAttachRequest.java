package com.teamflow.recruit.domain.dto;

/**
 * 简历版本关联请求（2.1.2）：文件已由 G-1 统一上传，本模块**只存 file_id**，不接收文件正文。
 */
public class RecruitResumeAttachRequest {

    private Long fileId;
    private String fileName;
    private String remark;

    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
