package com.teamflow.personal.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M10 复盘与成长日志表（{@code review_log}）访问层。
 *
 * <p>默认私有存本模块；主动公开时正文归档 M07，本表只留 {@code kb_page_id}（本期 M07 未开工，只预留字段）。</p>
 */
@Mapper
public interface ReviewLogMapper {

    @Insert("""
            INSERT INTO review_log (user_id, review_type, review_title, period_start, period_end, content,
                                    tags, visibility, create_by, update_by)
            VALUES (#{row.userId}, #{row.reviewType}, #{row.reviewTitle}, #{row.periodStart}, #{row.periodEnd},
                    #{row.content}, #{row.tags}, #{row.visibility}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertReview(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id, user_id AS userId, review_type AS reviewType, review_title AS reviewTitle,
                   DATE_FORMAT(period_start, '%Y-%m-%d') AS periodStart,
                   DATE_FORMAT(period_end, '%Y-%m-%d') AS periodEnd,
                   content, tags, visibility, kb_page_id AS kbPageId,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM review_log
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectById(@Param("id") Long id);

    @Select("""
            <script>
            SELECT COUNT(*) FROM review_log
            WHERE user_id = #{userId} AND deleted = 0
              <if test="reviewType != null and reviewType != ''"> AND review_type = #{reviewType} </if>
            </script>
            """)
    long countReviews(@Param("userId") Long userId, @Param("reviewType") String reviewType);

    @Select("""
            <script>
            SELECT id, review_type AS reviewType, review_title AS reviewTitle,
                   DATE_FORMAT(period_start, '%Y-%m-%d') AS periodStart,
                   DATE_FORMAT(period_end, '%Y-%m-%d') AS periodEnd,
                   tags, visibility,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i') AS createTime
            FROM review_log
            WHERE user_id = #{userId} AND deleted = 0
              <if test="reviewType != null and reviewType != ''"> AND review_type = #{reviewType} </if>
            ORDER BY create_time DESC, id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectReviews(
            @Param("userId") Long userId,
            @Param("reviewType") String reviewType,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Update("""
            UPDATE review_log
            SET review_type = #{row.reviewType},
                review_title = #{row.reviewTitle},
                period_start = #{row.periodStart},
                period_end = #{row.periodEnd},
                content = #{row.content},
                tags = #{row.tags},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateReview(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE review_log
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDelete(@Param("id") Long id, @Param("operator") String operator);

    @Select("SELECT COUNT(*) FROM review_log WHERE user_id = #{userId} AND deleted = 0")
    long countByUser(@Param("userId") Long userId);
}
