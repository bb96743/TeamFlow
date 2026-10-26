package com.teamflow.meeting.domain.dto;

import java.util.List;

/**
 * 会议纪要归档请求（M3-C 核心）：正文归档到知识模块，本模块只保存返回的 pageId。
 */
public class MeetingMinutesRequest {

    /** 纪要正文（Markdown），归档到知识模块；本模块不落库正文。 */
    private String content;
    /** 归档页面摘要（可空，如会议结论/决议摘要）。 */
    private String summary;
    private List<String> tags;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
