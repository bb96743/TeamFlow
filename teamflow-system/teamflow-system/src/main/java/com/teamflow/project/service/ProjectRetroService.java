package com.teamflow.project.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.knowledge.domain.KnowledgeArchiveRequest;
import com.teamflow.knowledge.domain.KnowledgeArchiveResult;
import com.teamflow.knowledge.service.KnowledgeArchiveService;
import com.teamflow.project.domain.dto.ProjectRetroArchiveRequest;
import com.teamflow.project.mapper.ProjectMapper;
import com.teamflow.project.mapper.ProjectPlatformMapper;
import com.teamflow.project.stage.ProjectStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M14 项目复盘服务（需求 2.5.4）。
 *
 * <p><b>归档契约（L0 §9.1 D-2026-10-26-11 结论三）</b>：复盘正文**只写知识模块页面**，
 * 本模块只保存归档返回的 {@code retro_page_id}（外加归档时间与操作人），**不复制正文**。
 * 同一项目重复归档由知识模块按来源三要素（M14 / project_retro / projectId）幂等处理并保留版本历史。</p>
 *
 * <p>归档前置：项目必须已结项（{@code closed}）或已归档（{@code archived}），保证"结项复盘"语义。</p>
 */
@Service
public class ProjectRetroService extends ProjectServiceSupport {

    private static final String SOURCE_MODULE = "M14";
    private static final String SOURCE_BIZ_TYPE = "project_retro";

    private final ProjectMapper projectMapper;
    private final ProjectMilestoneService milestoneService;
    /** M07 跨模块归档 Service（只注入，不改知识模块代码）。 */
    private final KnowledgeArchiveService archiveService;

    public ProjectRetroService(
            ProjectPlatformMapper platformMapper,
            ProjectMapper projectMapper,
            ProjectMilestoneService milestoneService,
            KnowledgeArchiveService archiveService
    ) {
        super(platformMapper);
        this.projectMapper = projectMapper;
        this.milestoneService = milestoneService;
        this.archiveService = archiveService;
    }

    /** 复盘状态：已归档时返回引用信息（正文仍在知识模块，不在此返回）。 */
    @DataScope
    public Map<String, Object> retro(Long projectId) {
        Map<String, Object> project = requireVisibleProject(projectId, DataScopeContext.current());
        return retroView(project);
    }

    /**
     * 结项复盘模板（Markdown 骨架）：预填项目概况与里程碑完成情况，正文由用户补充后归档到知识模块。
     */
    @DataScope
    public Map<String, Object> template(Long projectId) {
        Map<String, Object> project = requireVisibleProject(projectId, DataScopeContext.current());
        String projectName = stringOf(project.get("projectName"));
        List<Map<String, Object>> milestones = milestoneService.listForProject(projectId);
        Map<String, Object> progress = milestoneService.projectProgress(milestones);
        StringBuilder content = new StringBuilder();
        content.append("# 项目复盘：").append(projectName).append("\n\n");
        content.append("## 一、项目概况\n\n");
        content.append("- 项目编号：").append(nullToDash(project.get("projectCode"))).append("\n");
        content.append("- 项目负责人：").append(nullToDash(project.get("ownerName"))).append("\n");
        content.append("- 计划周期：").append(nullToDash(project.get("startDate"))).append(" 至 ")
                .append(nullToDash(project.get("endDate"))).append("\n");
        content.append("- 当前状态：").append(ProjectStatusMachine.projectLabel(stringOf(project.get("status"))))
                .append("\n\n");
        content.append("## 二、目标达成情况\n\n");
        content.append("- 里程碑完成：").append(longOf(progress.get("completedMilestoneCount")))
                .append(" / ").append(longOf(progress.get("milestoneCount"))).append("\n");
        Object projectProgress = progress.get("projectProgress");
        content.append("- 项目完成度：").append(projectProgress == null ? "暂无可汇总完成度" : projectProgress + "%")
                .append("\n");
        content.append("- 延期里程碑：").append(longOf(progress.get("delayedMilestoneCount"))).append(" 个\n\n");
        content.append("### 里程碑清单\n\n");
        if (milestones.isEmpty()) {
            content.append("- （未设置里程碑）\n");
        } else {
            for (Map<String, Object> milestone : milestones) {
                content.append("- ").append(nullToDash(milestone.get("milestoneName")))
                        .append("（").append(nullToDash(milestone.get("statusLabel")))
                        .append("，计划完成 ").append(nullToDash(milestone.get("planEndDate")))
                        .append("，完成度 ").append(milestone.get("completion") == null
                                ? "—" : milestone.get("completion") + "%")
                        .append("）\n");
            }
        }
        content.append("\n## 三、关键过程与决策\n\n- \n\n");
        content.append("## 四、问题与风险\n\n- \n\n");
        content.append("## 五、经验与改进措施\n\n- \n\n");
        content.append("## 六、后续行动项\n\n- \n");
        Map<String, Object> result = new LinkedHashMap<>(retroView(project));
        result.put("templateTitle", "项目复盘：" + projectName);
        result.put("templateContent", content.toString());
        result.put("templateNote", "模板仅预填项目与里程碑事实；正文由填写人补充，归档后正文只存知识模块");
        return result;
    }

    /**
     * 归档复盘：正文写入知识模块页面，本模块只落 {@code retro_page_id}。
     */
    @Transactional
    @DataScope
    public Map<String, Object> archive(Long projectId, ProjectRetroArchiveRequest request) {
        Map<String, Object> project = requireVisibleProject(projectId, DataScopeContext.current());
        String status = stringOf(project.get("status"));
        if (!ProjectStatusMachine.PROJECT_CLOSED.equals(status)
                && !ProjectStatusMachine.PROJECT_ARCHIVED.equals(status)) {
            throw new BusinessException("项目结项后才能归档复盘（当前状态：" + ProjectStatusMachine.projectLabel(status) + "）");
        }
        String content = required(request == null ? null : request.content(), "复盘正文不能为空");
        String title = defaultString(request.title(), "项目复盘：" + stringOf(project.get("projectName")));
        Long operatorId = currentUserId();
        String operatorName = currentUsername();
        KnowledgeArchiveResult archived = archiveService.archive(new KnowledgeArchiveRequest(
                SOURCE_MODULE,
                SOURCE_BIZ_TYPE,
                projectId,
                null,
                null,
                title,
                content,
                request == null ? null : request.tags(),
                request == null ? null : trimToNull(request.summary()),
                operatorId,
                operatorName,
                true));
        projectMapper.updateRetroArchive(projectId, archived.pageId(), LocalDateTime.now(), operatorName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        result.put("retroPageId", archived.pageId());
        result.put("created", archived.created());
        result.put("versionNo", archived.versionNo());
        result.put("pageType", archived.pageType());
        result.put("spaceId", archived.spaceId());
        result.put("nodeId", archived.nodeId());
        result.put("contentStoredIn", "M07 知识模块（本模块只存 retro_page_id，不复制正文）");
        return result;
    }

    // ------------------------------------------------------------------

    private Map<String, Object> retroView(Map<String, Object> project) {
        String status = stringOf(project.get("status"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", numberAsLong(project.get("id")));
        result.put("projectName", project.get("projectName"));
        result.put("projectCode", project.get("projectCode"));
        result.put("status", status);
        result.put("statusLabel", ProjectStatusMachine.projectLabel(status));
        result.put("retroPageId", project.get("retroPageId"));
        result.put("retroArchivedTime", project.get("retroArchivedTime"));
        result.put("retroArchivedBy", project.get("retroArchivedBy"));
        result.put("retroArchived", project.get("retroPageId") != null);
        result.put("canArchive", ProjectStatusMachine.PROJECT_CLOSED.equals(status)
                || ProjectStatusMachine.PROJECT_ARCHIVED.equals(status));
        result.put("archiveRule", "项目结项（closed）或归档（archived）后可归档复盘；重复归档生成知识页面新版本");
        return result;
    }

    private Map<String, Object> requireVisibleProject(Long projectId, DataScopeCriteria scope) {
        if (projectId == null) {
            throw new BusinessException("项目 ID 不能为空");
        }
        Map<String, Object> project = projectMapper.selectProject(projectId, scope);
        if (project == null) {
            throw new BusinessException("项目不存在或无权访问");
        }
        return project;
    }

    private String nullToDash(Object value) {
        String text = value == null ? null : String.valueOf(value);
        return text == null || text.isBlank() ? "—" : text;
    }

    private String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
