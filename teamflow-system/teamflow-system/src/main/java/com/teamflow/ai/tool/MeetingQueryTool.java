package com.teamflow.ai.tool;

import com.teamflow.ai.domain.AiCaller;
import com.teamflow.ai.support.AiDates;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.meeting.service.MeetingMeetingService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：查询会议/日程（meeting.query）。
 *
 * <p>调用 M06 的会议列表 Service（{@code @DataScope}：仅主持人/参会人可见），
 * 用于「本周有哪些会」「我的会议」这类问题，也是日报/周报的会议记录来源。</p>
 */
@Component
public class MeetingQueryTool implements AiTool {

    private final MeetingMeetingService meetingService;
    private final AiToolSupport support;

    public MeetingQueryTool(MeetingMeetingService meetingService, AiToolSupport support) {
        this.meetingService = meetingService;
        this.support = support;
    }

    @Override
    public String name() {
        return "meeting.query";
    }

    @Override
    public String label() {
        return "查询会议";
    }

    @Override
    public String permissionCode() {
        return "meeting:meeting:list";
    }

    @Override
    public ToolOutcome execute(Map<String, Object> params, AiCaller caller) {
        LocalDate[] range = support.range(support.text(params, "range", ""), LocalDate.now());
        String fromTime = AiDates.dateTime(range[0]);
        String toTime = AiDates.endOfDay(range[1]);
        PageResult<Map<String, Object>> page = meetingService.list(
                new PageQuery(1, 30, null, null), null, null, fromTime, toTime);
        List<Map<String, Object>> rows = page.rows() == null ? List.of() : page.rows();
        StringBuilder summary = new StringBuilder();
        summary.append("区间：").append(support.rangeLabel(range))
                .append("｜可见会议共 ").append(page.total()).append(" 场：");
        int index = 1;
        for (Map<String, Object> row : rows.stream().limit(10).toList()) {
            summary.append("\n").append(index++).append(". ")
                    .append(support.text(row, "meetingTitle"))
                    .append("｜").append(support.text(row, "startTime"))
                    .append(" ~ ").append(support.text(row, "endTime"));
            String roomName = support.text(row, "roomName");
            if (!roomName.isBlank()) {
                summary.append("｜").append(roomName);
            }
            summary.append("｜状态：").append(support.text(row, "status"));
        }
        if (rows.isEmpty()) {
            summary.append("\n（该区间没有可见会议）");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("range", support.rangeLabel(range));
        data.put("total", page.total());
        data.put("meetings", rows.stream().limit(20).toList());
        return ToolOutcome.ok(name(), params, permissionCode(), summary.toString(), data);
    }
}
