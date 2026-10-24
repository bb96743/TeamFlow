package com.teamflow.okr.service;

import com.teamflow.common.exception.BusinessException;
import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.okr.domain.dto.OkrAlignWriteRequest;
import com.teamflow.okr.domain.vo.OkrIdResponse;
import com.teamflow.okr.mapper.OkrAlignMapper;
import com.teamflow.okr.mapper.OkrObjectiveMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M02 目标对齐关系服务（M02 RULE R6）。
 *
 * <p>对齐关系单向保存为「子目标 → 父目标」；每个子目标最多一条有效对齐关系；
 * 保存前沿父链回溯检测环形引用，命中即拒绝。</p>
 */
@Service
public class OkrAlignService extends OkrServiceSupport {

    private static final Set<String> ALIGN_TYPES = Set.of("parent", "support");
    private static final int CHAIN_GUARD = 100;

    private final OkrAlignMapper alignMapper;
    private final OkrObjectiveMapper objectiveMapper;
    private final OkrObjectiveService objectiveService;
    private final OkrPeriodService periodService;

    public OkrAlignService(
            OkrAlignMapper alignMapper,
            OkrObjectiveMapper objectiveMapper,
            OkrObjectiveService objectiveService,
            OkrPeriodService periodService
    ) {
        this.alignMapper = alignMapper;
        this.objectiveMapper = objectiveMapper;
        this.objectiveService = objectiveService;
        this.periodService = periodService;
    }

    @DataScope
    public List<Map<String, Object>> list(Long objectiveId) {
        if (objectiveService.visibleObjective(objectiveId) == null) {
            throw new BusinessException("目标不存在或无权访问");
        }
        return alignMapper.selectAlignsByObjective(objectiveId, DataScopeContext.current());
    }

    @Transactional
    public OkrIdResponse create(Long childObjectiveId, OkrAlignWriteRequest request) {
        Map<String, Object> child = objectiveService.requireObjective(childObjectiveId);
        periodService.requireEditable(numberAsLong(child.get("periodId")));
        Long parentObjectiveId = request.getParentObjectiveId();
        if (parentObjectiveId == null) {
            throw new BusinessException("父目标不能为空");
        }
        if (parentObjectiveId.equals(childObjectiveId)) {
            throw new BusinessException("目标不能对齐到自身");
        }
        objectiveService.requireObjective(parentObjectiveId);
        if (alignMapper.countAlignPair(childObjectiveId, parentObjectiveId) > 0) {
            throw new BusinessException("对齐关系已存在");
        }
        if (alignMapper.selectParentObjectiveId(childObjectiveId) != null) {
            throw new BusinessException("该目标已存在上级对齐关系，请先删除原对齐关系");
        }
        ensureNoCycle(childObjectiveId, parentObjectiveId);
        String alignType = defaultString(request.getAlignType(), "parent");
        if (!ALIGN_TYPES.contains(alignType)) {
            throw new BusinessException("对齐类型只能为 parent（上级对齐）或 support（协同支持）");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("childObjectiveId", childObjectiveId);
        row.put("parentObjectiveId", parentObjectiveId);
        row.put("alignType", alignType);
        row.put("remark", trimToNull(request.getRemark()));
        alignMapper.insertAlign(row, currentUsername());
        return new OkrIdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void delete(Long alignId) {
        Map<String, Object> align = alignMapper.selectAlignById(alignId);
        if (align == null) {
            throw new BusinessException("对齐关系不存在");
        }
        Long childObjectiveId = numberAsLong(align.get("childObjectiveId"));
        Map<String, Object> child = objectiveMapper.selectObjectiveById(childObjectiveId);
        if (child != null) {
            periodService.requireEditable(numberAsLong(child.get("periodId")));
        }
        alignMapper.softDeleteAlign(alignId, currentUsername());
    }

    /** 沿「子目标 → 父目标」链条回溯，若回到当前子目标则说明新增该关系会形成环。 */
    private void ensureNoCycle(Long childObjectiveId, Long parentObjectiveId) {
        Set<Long> visited = new HashSet<>();
        Long cursor = parentObjectiveId;
        int guard = 0;
        while (cursor != null && cursor > 0 && guard++ < CHAIN_GUARD) {
            if (cursor.equals(childObjectiveId)) {
                throw new BusinessException("对齐关系存在环形引用，禁止保存");
            }
            if (!visited.add(cursor)) {
                throw new BusinessException("对齐关系存在环形引用，禁止保存");
            }
            cursor = alignMapper.selectParentObjectiveId(cursor);
        }
    }
}
