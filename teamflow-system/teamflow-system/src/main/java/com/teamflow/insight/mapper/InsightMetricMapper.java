package com.teamflow.insight.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M13 指标字典表（{@code insight_metric}）访问层。
 *
 * <p>本表是 M13 **唯一的自有表**，只存指标定义与口径（定义/公式/来源/时间口径/缺失值策略/刷新频率），
 * **不存任何看板明细数据**（M13 RULE §四）。</p>
 */
@Mapper
public interface InsightMetricMapper {

    String COLUMNS = """
            SELECT m.id,
                   m.metric_code AS metricCode,
                   m.metric_name AS metricName,
                   m.metric_category AS metricCategory,
                   m.metric_definition AS metricDefinition,
                   m.formula,
                   m.source_module AS sourceModule,
                   m.source_detail AS sourceDetail,
                   m.time_grain AS timeGrain,
                   m.missing_policy AS missingPolicy,
                   m.refresh_frequency AS refreshFrequency,
                   m.cache_ttl_seconds AS cacheTtlSeconds,
                   m.owner_name AS ownerName,
                   m.status,
                   m.remark,
                   DATE_FORMAT(m.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(m.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM insight_metric m
            """;

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM insight_metric m
            WHERE m.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (m.metric_code LIKE CONCAT('%', #{keyword}, '%') OR m.metric_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="category != null and category != ''">
              AND m.metric_category = #{category}
              </if>
              <if test="status != null and status != ''">
              AND m.status = #{status}
              </if>
            </script>
            """)
    long countMetrics(
            @Param("keyword") String keyword,
            @Param("category") String category,
            @Param("status") String status
    );

    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE m.deleted = 0
              <if test="keyword != null and keyword != ''">
              AND (m.metric_code LIKE CONCAT('%', #{keyword}, '%') OR m.metric_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="category != null and category != ''">
              AND m.metric_category = #{category}
              </if>
              <if test="status != null and status != ''">
              AND m.status = #{status}
              </if>
            ORDER BY m.metric_category, m.id
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectMetrics(
            @Param("keyword") String keyword,
            @Param("category") String category,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select(COLUMNS + " WHERE m.id = #{id} AND m.deleted = 0 LIMIT 1")
    Map<String, Object> selectMetricById(@Param("id") Long id);

    @Select(COLUMNS + " WHERE m.metric_code = #{metricCode} AND m.deleted = 0 LIMIT 1")
    Map<String, Object> selectMetricByCode(@Param("metricCode") String metricCode);

    /** 已上线指标（看板出数只认 status=online，且调用方还会校验代码侧登记表）。 */
    @Select(COLUMNS + " WHERE m.status = 'online' AND m.deleted = 0 ORDER BY m.id")
    List<Map<String, Object>> selectOnlineMetrics();

    @Select("""
            SELECT COUNT(*) FROM insight_metric
            WHERE metric_code = #{metricCode} AND deleted = 0
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    long countCode(@Param("metricCode") String metricCode, @Param("excludeId") Long excludeId);

    @Insert("""
            INSERT INTO insight_metric (metric_code, metric_name, metric_category, metric_definition, formula,
                                        source_module, source_detail, time_grain, missing_policy,
                                        refresh_frequency, cache_ttl_seconds, owner_name, status, remark,
                                        create_by, update_by)
            VALUES (#{row.metricCode}, #{row.metricName}, #{row.metricCategory}, #{row.metricDefinition},
                    #{row.formula}, #{row.sourceModule}, #{row.sourceDetail}, #{row.timeGrain},
                    #{row.missingPolicy}, #{row.refreshFrequency}, #{row.cacheTtlSeconds}, #{row.ownerName},
                    #{row.status}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertMetric(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE insight_metric
            SET metric_name = #{row.metricName},
                metric_category = #{row.metricCategory},
                metric_definition = #{row.metricDefinition},
                formula = #{row.formula},
                source_module = #{row.sourceModule},
                source_detail = #{row.sourceDetail},
                time_grain = #{row.timeGrain},
                missing_policy = #{row.missingPolicy},
                refresh_frequency = #{row.refreshFrequency},
                cache_ttl_seconds = #{row.cacheTtlSeconds},
                owner_name = #{row.ownerName},
                status = #{row.status},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateMetric(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("UPDATE insight_metric SET deleted = 1, update_by = #{operator} WHERE id = #{id} AND deleted = 0")
    int softDeleteMetric(@Param("id") Long id, @Param("operator") String operator);
}
