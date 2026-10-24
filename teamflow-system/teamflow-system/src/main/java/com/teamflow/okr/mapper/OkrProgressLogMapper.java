package com.teamflow.okr.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M02 进度留痕表（{@code okr_progress_log}）访问层。
 *
 * <p>只追加、不修改历史（M02 RULE R1）；读取时的数据范围由所属 KR 的可见性校验保证。</p>
 */
@Mapper
public interface OkrProgressLogMapper {

    @Insert("""
            INSERT INTO okr_progress_log (kr_id, objective_id, from_progress, to_progress, progress_source, reason,
                                          operator_id, operator_name, create_by, update_by)
            VALUES (#{row.krId}, #{row.objectiveId}, #{row.fromProgress}, #{row.toProgress}, #{row.progressSource},
                    #{row.reason}, #{row.operatorId}, #{row.operatorName}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertProgressLog(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT l.id,
                   l.kr_id AS krId,
                   l.objective_id AS objectiveId,
                   l.from_progress AS fromProgress,
                   l.to_progress AS toProgress,
                   l.progress_source AS progressSource,
                   l.reason,
                   l.operator_id AS operatorId,
                   l.operator_name AS operatorName,
                   DATE_FORMAT(l.create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM okr_progress_log l
            WHERE l.kr_id = #{krId} AND l.deleted = 0
            ORDER BY l.id DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectProgressLogs(@Param("krId") Long krId, @Param("limit") int limit);
}
