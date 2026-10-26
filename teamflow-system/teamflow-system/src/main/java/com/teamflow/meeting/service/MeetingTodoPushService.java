package com.teamflow.meeting.service;

import com.teamflow.meeting.mapper.MeetingPlatformMapper;
import com.teamflow.personal.service.TodoPushService;
import com.teamflow.personal.todo.TodoBizTypes;
import com.teamflow.personal.todo.TodoPushCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * M06 对 M10 待办收件箱的投递适配（D-2026-10-26-11 结论四、M10 RULE R1）。
 *
 * <p><b>边界</b>：本类只调用 M10 的统一投递服务 {@link TodoPushService}（与
 * {@code POST /api/personal/todos/internal/push} 共用同一实现），**绝不直写待办表**；
 * M06 也不读 M10 的表。</p>
 *
 * <p><b>bizType 选择</b>：M10 已登记 {@code meeting_invite}（为 M06 预留，本期无 handler）。
 * 为避免修改 M10 登记表，本模块用同一 bizType + 不同 {@code actionType} 区分两类待办：
 * <ul>
 *   <li>{@code actionType=attend}：会议邀请/参会确认（创建、改期、加人时投递给参会人）；</li>
 *   <li>{@code actionType=write_minutes}：会议结束后给主持人的「撰写会议纪要」待办（R4）。</li>
 * </ul>
 * 后续 M10 若登记会议纪要专用 bizType，只需替换常量、回收本类即可。</p>
 */
@Service
public class MeetingTodoPushService extends MeetingServiceSupport {

    private static final Logger log = LoggerFactory.getLogger(MeetingTodoPushService.class);

    private final TodoPushService todoPushService;

    public MeetingTodoPushService(MeetingPlatformMapper platformMapper, TodoPushService todoPushService) {
        super(platformMapper);
        this.todoPushService = todoPushService;
    }

    /** 给参会人投递「会议邀请/参会确认」待办。 */
    public void pushAttendTodos(Long meetingId, String title, LocalDateTime start, List<Long> receiverIds) {
        if (receiverIds == null || receiverIds.isEmpty()) {
            return;
        }
        Set<Long> unique = new LinkedHashSet<>();
        receiverIds.stream().filter(id -> id != null).forEach(unique::add);
        for (Long receiverId : unique) {
            try {
                todoPushService.push(new TodoPushCommand(
                        TodoBizTypes.MEETING_INVITE,
                        meetingId,
                        receiverId,
                        "attend",
                        "会议邀请：" + title,
                        "/meeting/list?meetingId=" + meetingId,
                        start,
                        "normal",
                        null));
            } catch (RuntimeException exception) {
                log.warn("会议邀请待办投递失败：meetingId={} userId={} error={}", meetingId, receiverId, exception.getMessage());
            }
        }
    }

    /**
     * 会议结束后给主持人投递「撰写会议纪要」待办（R4：会议结束必须产出可执行项）。
     *
     * @return M10 待办 ID；无主持人或投递失败时返回 null
     */
    public Long pushMinutesTodo(Long meetingId, Long organizerId, String title, LocalDateTime endTime) {
        if (organizerId == null) {
            return null;
        }
        try {
            return todoPushService.push(new TodoPushCommand(
                    TodoBizTypes.MEETING_INVITE,
                    meetingId,
                    organizerId,
                    "write_minutes",
                    "撰写会议纪要：" + title,
                    "/meeting/list?meetingId=" + meetingId + "&tab=minutes",
                    endTime == null ? null : endTime.plusDays(1),
                    "normal",
                    null));
        } catch (RuntimeException exception) {
            log.warn("会议纪要待办投递失败：meetingId={} organizerId={} error={}",
                    meetingId, organizerId, exception.getMessage());
            return null;
        }
    }
}
