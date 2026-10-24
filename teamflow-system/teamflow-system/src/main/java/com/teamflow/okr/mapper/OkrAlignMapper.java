package com.teamflow.okr.mapper;

import com.teamflow.framework.datascope.DataScopeCriteria;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M02 目标对齐关系表（{@code okr_align}）访问层。
 *
 * <p>关系单向：{@code child_objective_id → parent_objective_id}。环形引用在 Service 保存前检测（M02 RULE R6）。</p>
 */
@Mapper
public interface OkrAlignMapper {

    @Select("""
            <script>
            SELECT a.id,
                   a.child_objective_id AS childObjectiveId,
                   c.objective_title AS childTitle,
                   a.parent_objective_id AS parentObjectiveId,
                   po.objective_title AS parentTitle,
                   a.align_type AS alignType,
                   a.remark,
                   DATE_FORMAT(a.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM okr_align a
            JOIN okr_objective c ON c.id = a.child_objective_id AND c.deleted = 0
            LEFT JOIN okr_objective po ON po.id = a.parent_objective_id AND po.deleted = 0
            WHERE a.deleted = 0
              AND a.child_objective_id = #{objectiveId}
              AND (
                  #{scope.allScope} = TRUE
                  OR c.owner_id = #{scope.userId}
                  <if test="scope.deptIds != null and scope.deptIds.size > 0">
                  OR c.dept_id IN
                  <foreach collection="scope.deptIds" item="scopeDeptId" open="(" separator="," close=")">
                      #{scopeDeptId}
                  </foreach>
                  </if>
              )
            ORDER BY a.id
            </script>
            """)
    List<Map<String, Object>> selectAlignsByObjective(
            @Param("objectiveId") Long objectiveId,
            @Param("scope") DataScopeCriteria scope
    );

    /** 内部校验用：不受数据范围限制。 */
    @Select("""
            SELECT id,
                   child_objective_id AS childObjectiveId,
                   parent_objective_id AS parentObjectiveId,
                   align_type AS alignType
            FROM okr_align
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectAlignById(@Param("id") Long id);

    /** 取某目标的上级目标 ID（每个子目标最多一条有效对齐关系）。 */
    @Select("""
            SELECT parent_objective_id
            FROM okr_align
            WHERE child_objective_id = #{childObjectiveId} AND deleted = 0
            ORDER BY id
            LIMIT 1
            """)
    Long selectParentObjectiveId(@Param("childObjectiveId") Long childObjectiveId);

    @Select("""
            SELECT COUNT(*)
            FROM okr_align
            WHERE child_objective_id = #{childObjectiveId}
              AND parent_objective_id = #{parentObjectiveId}
              AND deleted = 0
            """)
    long countAlignPair(
            @Param("childObjectiveId") Long childObjectiveId,
            @Param("parentObjectiveId") Long parentObjectiveId
    );

    @Insert("""
            INSERT INTO okr_align (child_objective_id, parent_objective_id, align_type, remark, create_by, update_by)
            VALUES (#{row.childObjectiveId}, #{row.parentObjectiveId}, #{row.alignType}, #{row.remark},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertAlign(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE okr_align
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteAlign(@Param("id") Long id, @Param("operator") String operator);

    /** 目标删除时清理与之相关的对齐关系，避免遗留悬挂引用。 */
    @Update("""
            UPDATE okr_align
            SET deleted = 1, update_by = #{operator}
            WHERE (child_objective_id = #{objectiveId} OR parent_objective_id = #{objectiveId})
              AND deleted = 0
            """)
    int softDeleteAlignsOfObjective(@Param("objectiveId") Long objectiveId, @Param("operator") String operator);
}
