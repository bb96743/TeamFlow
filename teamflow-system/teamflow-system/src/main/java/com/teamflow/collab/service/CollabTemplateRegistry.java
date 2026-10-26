package com.teamflow.collab.service;

import com.teamflow.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * M09 通知模板注册表（R3：模板集中管理，禁止在业务代码里拼字符串；不接收裸 HTML）。
 *
 * <p>本期为代码内模板（M3-A 范围），模板编码由本类集中登记；未来需要版本化/可视化时
 * 可平滑替换为配置表，调用方只依赖 {@link #render(String, Map)}。</p>
 */
@Component
public class CollabTemplateRegistry {

    /** 模板渲染结果（标题 + 正文，纯文本）。 */
    public record Rendered(String title, String content) {
    }

    private static final Set<String> SUPPORTED = Set.of("mention", "announcement", "task_assigned", "system");

    public boolean supports(String templateCode) {
        return templateCode != null && SUPPORTED.contains(templateCode);
    }

    public Set<String> supportedCodes() {
        return Set.copyOf(SUPPORTED);
    }

    /**
     * 按模板编码渲染纯文本通知。
     *
     * @param templateCode 模板编码（见 {@link #supportedCodes()}）
     * @param params       模板参数（缺失参数按空串处理）
     */
    public Rendered render(String templateCode, Map<String, Object> params) {
        if (!supports(templateCode)) {
            throw new BusinessException("不支持的通知模板编码：" + templateCode
                    + "，可用编码为 " + String.join("、", SUPPORTED));
        }
        Map<String, Object> values = params == null ? Map.of() : params;
        return switch (templateCode) {
            case "mention" -> new Rendered(
                    text(values, "title", "%s 在评论/消息中提到了你".formatted(text(values, "actorName", "有人"))),
                    text(values, "content", "你有一条新的提及，点击查看并回复。"));
            case "announcement" -> new Rendered(
                    text(values, "title", "新公告：" + text(values, "noticeTitle", "")),
                    text(values, "content", text(values, "noticeTitle", "有一条新公告")));
            case "task_assigned" -> new Rendered(
                    text(values, "title", "你有一个新任务：" + text(values, "taskTitle", "")),
                    text(values, "content", "任务已指派给你，请及时处理。"));
            default -> new Rendered(
                    text(values, "title", "系统通知"),
                    text(values, "content", "你有一条新的系统通知。"));
        };
    }

    private String text(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }
}
