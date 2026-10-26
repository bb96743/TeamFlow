package com.teamflow.project.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.project.domain.dto.ProjectMemberAddRequest;
import com.teamflow.project.domain.vo.ProjectIdResponse;
import com.teamflow.project.mapper.ProjectMapper;
import com.teamflow.project.mapper.ProjectMemberMapper;
import com.teamflow.project.mapper.ProjectPlatformMapper;
import com.teamflow.project.stage.ProjectStatusMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M14 项目成员服务：负责人与参与人（项目内角色，不映射系统权限）。
 *
 * <p>移出成员只写 {@code leave_time} 不删行（历史可追溯）；再次加入时复活同一行。
 * 负责人由项目主表的 {@code owner_id} 唯一确定，成员接口不接受把某人设为 owner，
 * 也不允许移出 owner 行（避免项目无人负责）。</p>
 */
@Service
public class ProjectMemberService extends ProjectServiceSupport {

    private final ProjectMemberMapper memberMapper;
    private final ProjectMapper projectMapper;

    public ProjectMemberService(
            ProjectPlatformMapper platformMapper,
            ProjectMemberMapper memberMapper,
            ProjectMapper projectMapper
    ) {
        super(platformMapper);
        this.memberMapper = memberMapper;
        this.projectMapper = projectMapper;
    }

    @DataScope
    public List<Map<String, Object>> list(Long projectId) {
        requireVisibleProject(projectId, DataScopeContext.current());
        return listForProject(projectId);
    }

    /** 供项目服务复用的内部读取（调用方已完成项目可见性校验）。 */
    public List<Map<String, Object>> listForProject(Long projectId) {
        List<Map<String, Object>> rows = memberMapper.selectByProject(projectId);
        rows.forEach(row -> row.put("memberRoleLabel",
                ProjectStatusMachine.memberRoleLabel(String.valueOf(row.get("memberRole")))));
        return rows;
    }

    @Transactional
    @DataScope
    public ProjectIdResponse add(Long projectId, ProjectMemberAddRequest request) {
        Map<String, Object> project = requireEditableProject(projectId, DataScopeContext.current());
        Long userId = request.getUserId();
        if (userId == null) {
            throw new BusinessException("成员用户 ID 不能为空");
        }
        if (platformMapper.countActiveUser(userId) == 0) {
            throw new BusinessException("成员用户不存在或已停用");
        }
        String role = defaultString(request.getMemberRole(), ProjectStatusMachine.ROLE_MEMBER);
        if (!ProjectStatusMachine.isMemberRole(role)) {
            throw new BusinessException("项目内角色只能为 member 或 viewer（负责人由项目主数据维护）");
        }
        if (ProjectStatusMachine.ROLE_OWNER.equals(role)) {
            throw new BusinessException("负责人由项目主数据维护（owner_id），成员接口只添加参与人/观察者");
        }
        Map<String, Object> existing = memberMapper.selectAnyByPair(projectId, userId);
        if (existing != null) {
            if (existing.get("leaveTime") == null && longOf(existing.get("deleted")) == 0) {
                throw new BusinessException("该用户已在项目中，无需重复添加");
            }
            memberMapper.reviveMember(numberAsLong(existing.get("id")), role,
                    trimToNull(request.getRemark()), currentUsername());
            return new ProjectIdResponse(numberAsLong(existing.get("id")));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectId", projectId);
        row.put("userId", userId);
        row.put("memberRole", role);
        row.put("remark", trimToNull(request.getRemark()));
        memberMapper.insertMember(row, currentUsername());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", numberAsLong(row.get("id")));
        result.put("projectId", projectId);
        result.put("userId", userId);
        result.put("memberRole", role);
        return new ProjectIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    @DataScope
    public void remove(Long projectId, Long memberId) {
        Map<String, Object> project = requireEditableProject(projectId, DataScopeContext.current());
        Map<String, Object> member = requireMember(projectId, memberId);
        if (ProjectStatusMachine.ROLE_OWNER.equals(String.valueOf(member.get("memberRole")))) {
            throw new BusinessException("不能移出项目负责人；如需更换负责人请在项目编辑中变更 owner_id");
        }
        Long ownerId = numberAsLong(project.get("ownerId"));
        if (ownerId != null && ownerId.equals(numberAsLong(member.get("userId")))) {
            throw new BusinessException("不能移出项目负责人；如需更换负责人请在项目编辑中变更 owner_id");
        }
        memberMapper.removeMember(memberId, currentUsername());
    }

    /** 项目创建/负责人变更时同步成员表里的 owner 行（保证成员列表能展示负责人）。 */
    @Transactional
    public void syncOwnerMember(Long projectId, Long ownerId) {
        if (ownerId == null) {
            return;
        }
        Map<String, Object> existing = memberMapper.selectAnyByPair(projectId, ownerId);
        if (existing != null) {
            memberMapper.reviveMember(numberAsLong(existing.get("id")), ProjectStatusMachine.ROLE_OWNER,
                    trimToNull((String) existing.get("remark")), currentUsername());
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectId", projectId);
        row.put("userId", ownerId);
        row.put("memberRole", ProjectStatusMachine.ROLE_OWNER);
        row.put("remark", "项目负责人（随项目主数据自动维护）");
        memberMapper.insertMember(row, currentUsername());
    }

    /** 负责人变更时把原 owner 成员行降级为参与人（若存在）。 */
    @Transactional
    public void demoteOwnerMember(Long projectId, Long previousOwnerId) {
        if (previousOwnerId == null) {
            return;
        }
        Map<String, Object> existing = memberMapper.selectAnyByPair(projectId, previousOwnerId);
        if (existing != null) {
            memberMapper.updateRole(numberAsLong(existing.get("id")), ProjectStatusMachine.ROLE_MEMBER,
                    currentUsername());
        }
    }

    /** 项目删除时级联软删成员行（调用方已校验项目为草稿且可见）。 */
    @Transactional
    public void cascadeSoftDelete(Long projectId) {
        memberMapper.softDeleteByProject(projectId, currentUsername());
    }

    public Map<String, Object> requireMember(Long projectId, Long memberId) {
        if (memberId == null) {
            throw new BusinessException("成员 ID 不能为空");
        }
        List<Map<String, Object>> rows = memberMapper.selectByProject(projectId);
        for (Map<String, Object> row : rows) {
            if (numberAsLong(row.get("id")).equals(memberId)) {
                return row;
            }
        }
        throw new BusinessException("项目成员不存在或已移出");
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

    private Map<String, Object> requireEditableProject(Long projectId, DataScopeCriteria scope) {
        Map<String, Object> project = requireVisibleProject(projectId, scope);
        String status = String.valueOf(project.get("status"));
        if (!ProjectStatusMachine.isProjectEditable(status)) {
            throw new BusinessException("项目当前状态为「" + ProjectStatusMachine.projectLabel(status)
                    + "」，已结项/已归档项目的成员只读");
        }
        return project;
    }
}
