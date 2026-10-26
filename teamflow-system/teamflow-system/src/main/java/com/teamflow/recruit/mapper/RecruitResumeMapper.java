package com.teamflow.recruit.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M08 简历版本表（{@code recruit_resume}）访问层。
 *
 * <p><b>只存 {@code file_id}（M00 sys_file.id）</b>，不存文件正文/磁盘路径；解析与筛查字段为 M11 预留，本期不写。</p>
 */
@Mapper
public interface RecruitResumeMapper {

    @Select("""
            SELECT r.id,
                   r.candidate_id AS candidateId,
                   r.file_id AS fileId,
                   r.file_name AS fileName,
                   r.version,
                   r.parse_status AS parseStatus,
                   r.screening_score AS screeningScore,
                   r.remark,
                   DATE_FORMAT(r.create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM recruit_resume r
            WHERE r.candidate_id = #{candidateId} AND r.deleted = 0
            ORDER BY r.version DESC
            """)
    List<Map<String, Object>> selectByCandidate(@Param("candidateId") Long candidateId);

    @Select("""
            SELECT id, candidate_id AS candidateId, file_id AS fileId, version, parse_status AS parseStatus
            FROM recruit_resume
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectResumeById(@Param("id") Long id);

    @Select("SELECT COALESCE(MAX(version), 0) FROM recruit_resume WHERE candidate_id = #{candidateId} AND deleted = 0")
    int selectMaxVersion(@Param("candidateId") Long candidateId);

    @Select("SELECT COUNT(*) FROM recruit_resume WHERE candidate_id = #{candidateId} AND deleted = 0")
    long countByCandidate(@Param("candidateId") Long candidateId);

    @Insert("""
            INSERT INTO recruit_resume (candidate_id, file_id, file_name, version, parse_status, remark,
                                        create_by, update_by)
            VALUES (#{row.candidateId}, #{row.fileId}, #{row.fileName}, #{row.version}, 'pending', #{row.remark},
                    #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertResume(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("UPDATE recruit_resume SET deleted = 1, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int softDeleteResume(@Param("id") Long id, @Param("operator") String operator);
}
