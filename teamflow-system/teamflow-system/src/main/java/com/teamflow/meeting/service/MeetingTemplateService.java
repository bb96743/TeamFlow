package com.teamflow.meeting.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.meeting.domain.dto.MeetingTemplateWriteRequest;
import com.teamflow.meeting.domain.vo.MeetingIdResponse;
import com.teamflow.meeting.mapper.MeetingPlatformMapper;
import com.teamflow.meeting.mapper.MeetingTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M06 会议模板服务：常规会议、目标校准会、复盘会等专项模板的维护。
 *
 * <p>创建会议时若未填议程，默认取模板的议程模板；模板只提供结构与默认值，不存会议正文。</p>
 */
@Service
public class MeetingTemplateService extends MeetingServiceSupport {

    private static final Set<String> TEMPLATE_TYPES = Set.of("normal", "okr_calibration", "retro");
    private static final Set<String> STATUSES = Set.of("active", "disabled");

    private final MeetingTemplateMapper templateMapper;

    public MeetingTemplateService(MeetingPlatformMapper platformMapper, MeetingTemplateMapper templateMapper) {
        super(platformMapper);
        this.templateMapper = templateMapper;
    }

    public List<Map<String, Object>> list(String keyword, String templateType, String status) {
        return templateMapper.selectTemplates(trimToNull(keyword), trimToNull(templateType), trimToNull(status));
    }

    public Map<String, Object> detail(Long id) {
        if (id == null) {
            throw new BusinessException("会议模板 ID 不能为空");
        }
        Map<String, Object> template = templateMapper.selectTemplateById(id);
        if (template == null) {
            throw new BusinessException("会议模板不存在");
        }
        return template;
    }

    @Transactional
    public MeetingIdResponse create(MeetingTemplateWriteRequest request) {
        String code = required(request == null ? null : request.getTemplateCode(), "模板编码不能为空");
        if (templateMapper.countTemplateCode(code, null) > 0) {
            throw new BusinessException("模板编码已存在：" + code);
        }
        String name = required(request.getTemplateName(), "模板名称不能为空");
        String type = validateType(defaultString(request.getTemplateType(), "normal"));
        String status = validateStatus(defaultString(request.getStatus(), "active"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("templateCode", code);
        row.put("templateName", name);
        row.put("templateType", type);
        row.put("description", trimToNull(request.getDescription()));
        row.put("agendaTemplate", trimToNull(request.getAgendaTemplate()));
        row.put("defaultDurationMinutes", request.getDefaultDurationMinutes() == null
                ? 60 : Math.max(5, request.getDefaultDurationMinutes()));
        row.put("status", status);
        row.put("sort", request.getSort() == null ? 0 : request.getSort());
        row.put("remark", trimToNull(request.getRemark()));
        templateMapper.insertTemplate(row, currentUsername());
        return new MeetingIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void update(Long id, MeetingTemplateWriteRequest request) {
        Map<String, Object> existing = detail(id);
        String name = request == null || request.getTemplateName() == null
                ? String.valueOf(existing.get("templateName"))
                : required(request.getTemplateName(), "模板名称不能为空");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("templateName", name);
        row.put("templateType", request == null || request.getTemplateType() == null
                ? String.valueOf(existing.get("templateType")) : validateType(request.getTemplateType()));
        row.put("description", request == null || request.getDescription() == null
                ? existing.get("description") : trimToNull(request.getDescription()));
        row.put("agendaTemplate", request == null || request.getAgendaTemplate() == null
                ? existing.get("agendaTemplate") : trimToNull(request.getAgendaTemplate()));
        row.put("defaultDurationMinutes", request == null || request.getDefaultDurationMinutes() == null
                ? intOf(existing.get("defaultDurationMinutes")) : Math.max(5, request.getDefaultDurationMinutes()));
        row.put("status", request == null || request.getStatus() == null
                ? String.valueOf(existing.get("status")) : validateStatus(request.getStatus()));
        row.put("sort", request == null || request.getSort() == null
                ? intOf(existing.get("sort")) : request.getSort());
        row.put("remark", request == null || request.getRemark() == null
                ? existing.get("remark") : trimToNull(request.getRemark()));
        templateMapper.updateTemplate(id, row, currentUsername());
    }

    @Transactional
    public void delete(Long id) {
        detail(id);
        if (templateMapper.softDeleteTemplate(id, currentUsername()) == 0) {
            throw new BusinessException("内置模板不能删除（可停用）");
        }
    }

    /** 会议创建时按模板 ID 取模板（不存在则报错）。 */
    Map<String, Object> requireTemplate(Long templateId) {
        if (templateId == null) {
            return null;
        }
        Map<String, Object> template = templateMapper.selectTemplateById(templateId);
        if (template == null) {
            throw new BusinessException("会议模板不存在：" + templateId);
        }
        return template;
    }

    private String validateType(String type) {
        String value = required(type, "模板类型不能为空");
        if (!TEMPLATE_TYPES.contains(value)) {
            throw new BusinessException("模板类型只能为 normal、okr_calibration 或 retro");
        }
        return value;
    }

    private String validateStatus(String status) {
        String value = required(status, "模板状态不能为空");
        if (!STATUSES.contains(value)) {
            throw new BusinessException("模板状态只能为 active 或 disabled");
        }
        return value;
    }
}
