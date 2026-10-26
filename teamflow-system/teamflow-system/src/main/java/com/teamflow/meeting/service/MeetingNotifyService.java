package com.teamflow.meeting.service;

import com.teamflow.collab.domain.dto.InternalNotifyRequest;
import com.teamflow.collab.service.CollabNotificationService;
import com.teamflow.meeting.mapper.MeetingPlatformMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M06 对 M09 统一通知的投递适配（D-2026-10-26-11 结论四：通知只投递不代劳）。
 *
 * <p><b>边界</b>：本类只调用 M09 的通知服务（{@link CollabNotificationService#notifyInternal}），
 * M06 **不建消息表、不自行发站内信**；模板使用 M09 已登记的 {@code system} 模板，
 * 标题与正文通过参数传入纯文本（不拼接 HTML）。</p>
 *
 * <p>投递按「来源 + 接收人」幂等：邀请按时间槽去重、改期按新时间槽去重、取消按会议去重；
 * 单个接收人失败只记告警，不影响会议主流程。</p>
 */
@Service
public class MeetingNotifyService extends MeetingServiceSupport {

    private static final Logger log = LoggerFactory.getLogger(MeetingNotifyService.class);

    private final CollabNotificationService notificationService;

    public MeetingNotifyService(MeetingPlatformMapper platformMapper, CollabNotificationService notificationService) {
        super(platformMapper);
        this.notificationService = notificationService;
    }

    /** 会议邀请通知（每位参会人一条）。 */
    public void notifyInvite(Long meetingId, String title, LocalDateTime start, LocalDateTime end,
                             String location, String meetingLink, List<Long> receiverIds) {
        String timeText = displayDateTime(start) + " ~ " + displayDateTime(end);
        String place = placeText(location, meetingLink);
        String content = "会议时间：" + timeText + "；" + place + "。请在会议管理中确认是否参加。";
        String slot = start.format(DB_FORMAT).replace(' ', '-');
        sendAll(meetingId, "会议邀请：" + title, content, "meeting:invite:" + meetingId + ":" + slot, receiverIds);
    }

    /** 会议改期通知。 */
    public void notifyReschedule(Long meetingId, String title, LocalDateTime start, LocalDateTime end,
                                 String location, String meetingLink, List<Long> receiverIds) {
        String timeText = displayDateTime(start) + " ~ " + displayDateTime(end);
        String place = placeText(location, meetingLink);
        String content = "会议已改期，新时间：" + timeText + "；" + place + "。";
        String slot = start.format(DB_FORMAT).replace(' ', '-');
        sendAll(meetingId, "会议改期：" + title, content, "meeting:reschedule:" + meetingId + ":" + slot, receiverIds);
    }

    /** 纪要归档通知：纪要正文已存入知识模块，本模块只给出页面引用。 */
    public void notifyMinutesArchived(Long meetingId, String meetingTitle, Long pageId, List<Long> receiverIds) {
        String content = "会议纪要已归档到知识库（页面 ID：" + pageId + "），可在会议管理或知识库中查看。";
        sendAll(meetingId, "会议纪要已归档：" + meetingTitle, content,
                "meeting:minutes:" + meetingId + ":" + pageId, receiverIds);
    }

    /** 会议取消通知。 */
    public void notifyCancel(Long meetingId, String title, String reason, List<Long> receiverIds) {
        String content = "会议已取消" + (reason == null ? "。" : "，原因：" + reason + "。");
        sendAll(meetingId, "会议取消：" + title, content, "meeting:cancel:" + meetingId, receiverIds);
    }

    private void sendAll(Long meetingId, String title, String content, String keyPrefix, List<Long> receiverIds) {
        if (receiverIds == null || receiverIds.isEmpty()) {
            return;
        }
        Set<Long> unique = new LinkedHashSet<>();
        receiverIds.stream().filter(id -> id != null).forEach(unique::add);
        for (Long receiverId : unique) {
            send(meetingId, title, content, keyPrefix + ":" + receiverId, receiverId);
        }
    }

    private void send(Long meetingId, String title, String content, String idempotencyKey, Long receiverId) {
        try {
            notificationService.notifyInternal(new InternalNotifyRequest(
                    receiverId,
                    "system",
                    Map.of("title", title, "content", content),
                    "meeting",
                    meetingId,
                    "/meeting/list?meetingId=" + meetingId,
                    idempotencyKey,
                    null));
        } catch (RuntimeException exception) {
            // 单个接收人失败（如账号刚被停用）不阻断会议主流程，记录告警便于排查
            log.warn("会议通知投递失败：meetingId={} receiverId={} key={} error={}",
                    meetingId, receiverId, idempotencyKey, exception.getMessage());
        }
    }

    private String placeText(String location, String meetingLink) {
        String place = trimToNull(location);
        String link = trimToNull(meetingLink);
        if (place != null && link != null) {
            return "地点：" + place + "；链接：" + link;
        }
        if (place != null) {
            return "地点：" + place;
        }
        if (link != null) {
            return "线上链接：" + link;
        }
        return "地点待定";
    }
}
