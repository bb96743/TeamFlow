package com.teamflow.project.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M14 项目风险登记与状态留痕访问层（需求 2.5.3）。
 *
 * <p>风险状态变更必须同时写一条 {@code proj_risk_log}（append-only）；本 Mapper 只提供原子写操作，
 * 状态机校验在 Service 层集中完成。</p>
 */
@Mapper
public interface ProjectRiskMapper {

    @Insert("""
            INSERT INTO proj_risk (project_id, risk_title, risk_desc, risk_level, risk_type, owner_id,
                                   response_plan, due_date, status, remark, create_by, update_by)
            VALUES (#{row.projectId}, #{row.riskTitle}, #{row.riskDesc}, #{row.riskLevel}, #{row.riskType},
                    #{row.ownerId}, #{row.responsePlan}, #{row.dueDate}, #{row.status}, #{row.remark},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertRisk(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            <script>
            SELECT r.id,
                   r.project_id AS projectId,
                   r.risk_title AS riskTitle,
                   r.risk_desc AS riskDesc,
                   r.risk_level AS riskLevel,
                   r.risk_type AS riskType,
                   r.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   r.response_plan AS responsePlan,
                   DATE_FORMAT(r.due_date, '%Y-%m-%d') AS dueDate,
                   r.status,
                   r.remark,
                   DATE_FORMAT(r.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(r.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM proj_risk r
            LEFT JOIN sys_user u ON u.id = r.owner_id
            WHERE r.project_id = #{projectId} AND r.deleted = 0
              <if test="status != null and status != ''"> AND r.status = #{status} </if>
            ORDER BY r.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectRisks(
            @Param("projectId") Long projectId,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            <script>
            SELECT COUNT(*) FROM proj_risk r
            WHERE r.project_id = #{projectId} AND r.deleted = 0
              <if test="status != null and status != ''"> AND r.status = #{status} </if>
            </script>
            """)
    long countRisks(@Param("projectId") Long projectId, @Param("status") String status);

    @Select("""
            SELECT r.id, r.project_id AS projectId, r.risk_title AS riskTitle, r.risk_desc AS riskDesc,
                   r.risk_level AS riskLevel, r.risk_type AS riskType, r.owner_id AS ownerId,
                   u.nickname AS ownerName,
                   r.response_plan AS responsePlan, DATE_FORMAT(r.due_date, '%Y-%m-%d') AS dueDate,
                   r.status, r.remark
            FROM proj_risk r
            LEFT JOIN sys_user u ON u.id = r.owner_id
            WHERE r.id = #{id} AND r.deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectRiskById(@Param("id") Long id);

    @Update("""
            UPDATE proj_risk
            SET risk_title = #{row.riskTitle},
                risk_desc = #{row.riskDesc},
                risk_level = #{row.riskLevel},
                risk_type = #{row.riskType},
                owner_id = #{row.ownerId},
                response_plan = #{row.responsePlan},
                due_date = #{row.dueDate},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateRisk(@Param("id") Long id, @Param("row") Map<String, Object> row,
                   @Param("operator") String operator);

    @Update("""
            UPDATE proj_risk
            SET status = #{status}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateRiskStatus(@Param("id") Long id, @Param("status") String status,
                         @Param("operator") String operator);

    @Update("""
            UPDATE proj_risk
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteRisk(@Param("id") Long id, @Param("operator") String operator);

    @Update("""
            UPDATE proj_risk
            SET deleted = 1, update_by = #{operator}
            WHERE project_id = #{projectId} AND deleted = 0
            """)
    int softDeleteByProject(@Param("projectId") Long projectId, @Param("operator") String operator);

    // ------------------------------------------------------------------
    // 状态留痕（append-only）
    // ------------------------------------------------------------------

    @Insert("""
            INSERT INTO proj_risk_log (risk_id, from_status, to_status, action_note, operator_id, operator_name,
                                       create_by, update_by)
            VALUES (#{riskId}, #{fromStatus}, #{toStatus}, #{actionNote}, #{operatorId}, #{operatorName},
                    #{operatorName}, #{operatorName})
            """)
    int insertLog(
            @Param("riskId") Long riskId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("actionNote") String actionNote,
            @Param("operatorId") Long operatorId,
            @Param("operatorName") String operatorName);

    @Select("""
            SELECT id, risk_id AS riskId, from_status AS fromStatus, to_status AS toStatus,
                   action_note AS actionNote, operator_id AS operatorId, operator_name AS operatorName,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM proj_risk_log
            WHERE risk_id = #{riskId} AND deleted = 0
            ORDER BY id
            """)
    List<Map<String, Object>> selectLogs(@Param("riskId") Long riskId);

    @Select("SELECT COUNT(*) FROM proj_risk_log WHERE risk_id = #{riskId} AND deleted = 0")
    long countLogs(@Param("riskId") Long riskId);

    /** 最近一次状态留痕的时间（用于接口回显）。 */
    @Select("""
            SELECT DATE_FORMAT(MAX(create_time), '%Y-%m-%d %H:%i:%s')
            FROM proj_risk_log WHERE risk_id = #{riskId} AND deleted = 0
            """)
    String lastLogTime(@Param("riskId") Long riskId);
}
