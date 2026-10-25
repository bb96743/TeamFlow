package com.teamflow.task.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M03 流程定义表（{@code flow_definition}）访问层。
 *
 * <p>自研轻量引擎（D-2026-10-25-01）：本期流程定义为线性节点清单，节点数据存 {@code nodes_json}。
 * <b>任务状态的允许迁移表不在这里</b>，唯一来源是 {@code TaskStatusMachine}，避免出现两份真相。</p>
 */
@Mapper
public interface FlowDefinitionMapper {

    String COLUMNS = """
            SELECT d.id,
                   d.def_code AS defCode,
                   d.def_name AS defName,
                   d.business_type AS businessType,
                   d.nodes_json AS nodesJson,
                   d.status,
                   d.remark,
                   DATE_FORMAT(d.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(d.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM flow_definition d
            WHERE d.deleted = 0
            """;

    @Select("""
            <script>
            SELECT COUNT(*) FROM flow_definition d
            WHERE d.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (d.def_code LIKE CONCAT('%', #{keyword}, '%') OR d.def_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="businessType != null and businessType != ''">
              AND d.business_type = #{businessType}
              </if>
            </script>
            """)
    long countDefinitions(@Param("keyword") String keyword, @Param("businessType") String businessType);

    @Select("""
            <script>
            """ + COLUMNS + """
              <if test="keyword != null and keyword != ''">
              AND (d.def_code LIKE CONCAT('%', #{keyword}, '%') OR d.def_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="businessType != null and businessType != ''">
              AND d.business_type = #{businessType}
              </if>
            ORDER BY d.id
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectDefinitions(
            @Param("keyword") String keyword,
            @Param("businessType") String businessType,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            """ + COLUMNS + """
              AND d.id = #{id}
            LIMIT 1
            """)
    Map<String, Object> selectDefinitionById(@Param("id") Long id);

    @Select("""
            """ + COLUMNS + """
              AND d.def_code = #{defCode}
            LIMIT 1
            """)
    Map<String, Object> selectDefinitionByCode(@Param("defCode") String defCode);

    @Select("""
            SELECT COUNT(*) FROM flow_definition
            WHERE def_code = #{defCode} AND deleted = 0
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    int countByCode(@Param("defCode") String defCode, @Param("excludeId") Long excludeId);

    @Insert("""
            INSERT INTO flow_definition (def_code, def_name, business_type, nodes_json, status, remark,
                                         create_by, update_by)
            VALUES (#{row.defCode}, #{row.defName}, #{row.businessType}, #{row.nodesJson}, #{row.status},
                    #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertDefinition(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE flow_definition
            SET def_code = #{row.defCode},
                def_name = #{row.defName},
                business_type = #{row.businessType},
                nodes_json = #{row.nodesJson},
                status = #{row.status},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateDefinition(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);
}
