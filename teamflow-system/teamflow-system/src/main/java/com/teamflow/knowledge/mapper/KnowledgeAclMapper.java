package com.teamflow.knowledge.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M07 访问控制表（{@code knowledge_acl}）访问层。
 *
 * <p>授权沿「空间 → 目录 → 页面」逐级继承；某一级存在该用户的授权行时，
 * 该级结论覆盖上级（deny 显式拒绝、read/write/manage 授予），由服务层按链计算。</p>
 */
@Mapper
public interface KnowledgeAclMapper {

    /** 当前用户拥有的全部授权行（用于一次性构建授权视图，避免逐资源查询）。 */
    @Select("""
            SELECT id, resource_type AS resourceType, resource_id AS resourceId,
                   subject_type AS subjectType, subject_id AS subjectId, permission, remark
            FROM knowledge_acl
            WHERE subject_type = #{subjectType} AND subject_id = #{subjectId} AND deleted = 0
            ORDER BY resource_type, resource_id
            """)
    List<Map<String, Object>> selectBySubject(
            @Param("subjectType") String subjectType,
            @Param("subjectId") Long subjectId
    );

    /** 某资源上的授权清单（管理界面展示，含被授权人昵称）。 */
    @Select("""
            SELECT a.id,
                   a.resource_type AS resourceType,
                   a.resource_id AS resourceId,
                   a.subject_type AS subjectType,
                   a.subject_id AS subjectId,
                   u.nickname AS subjectName,
                   u.username AS subjectUsername,
                   a.permission,
                   a.remark,
                   DATE_FORMAT(a.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM knowledge_acl a
            LEFT JOIN sys_user u ON u.id = a.subject_id
            WHERE a.resource_type = #{resourceType} AND a.resource_id = #{resourceId} AND a.deleted = 0
            ORDER BY a.id
            """)
    List<Map<String, Object>> selectByResource(
            @Param("resourceType") String resourceType,
            @Param("resourceId") Long resourceId
    );

    @Select("""
            SELECT id, resource_type AS resourceType, resource_id AS resourceId,
                   subject_type AS subjectType, subject_id AS subjectId, permission
            FROM knowledge_acl
            WHERE resource_type = #{resourceType}
              AND resource_id = #{resourceId}
              AND subject_type = #{subjectType}
              AND subject_id = #{subjectId}
              AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectOne(
            @Param("resourceType") String resourceType,
            @Param("resourceId") Long resourceId,
            @Param("subjectType") String subjectType,
            @Param("subjectId") Long subjectId
    );

    @Select("SELECT resource_type AS resourceType, resource_id AS resourceId FROM knowledge_acl WHERE id = #{id} AND deleted = 0 LIMIT 1")
    Map<String, Object> selectAclById(@Param("id") Long id);

    @Insert("""
            INSERT INTO knowledge_acl (resource_type, resource_id, subject_type, subject_id, permission, remark,
                                       create_by, update_by)
            VALUES (#{row.resourceType}, #{row.resourceId}, #{row.subjectType}, #{row.subjectId},
                    #{row.permission}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertAcl(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE knowledge_acl
            SET permission = #{permission}, remark = #{remark}, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateAcl(
            @Param("id") Long id,
            @Param("permission") String permission,
            @Param("remark") String remark,
            @Param("operator") String operator
    );

    @Update("""
            UPDATE knowledge_acl
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteAcl(@Param("id") Long id, @Param("operator") String operator);

    /** 资源删除时一并清理其授权行（避免悬挂授权）。 */
    @Update("""
            UPDATE knowledge_acl
            SET deleted = 1, update_by = #{operator}
            WHERE resource_type = #{resourceType} AND resource_id = #{resourceId} AND deleted = 0
            """)
    int softDeleteByResource(
            @Param("resourceType") String resourceType,
            @Param("resourceId") Long resourceId,
            @Param("operator") String operator
    );
}
