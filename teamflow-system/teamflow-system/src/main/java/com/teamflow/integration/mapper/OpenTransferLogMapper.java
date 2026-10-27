package com.teamflow.integration.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M12 导入导出批次审计表（{@code open_transfer_log}）访问层。
 *
 * <p>只记录统计与错误摘要（截断），不保存导入原文与导出全量数据。</p>
 */
@Mapper
public interface OpenTransferLogMapper {

    @Insert("""
            INSERT INTO open_transfer_log (direction, biz_type, file_name, total_rows, success_rows,
                                           failed_rows, status, detail, create_by, update_by)
            VALUES (#{row.direction}, #{row.bizType}, #{row.fileName}, #{row.totalRows}, #{row.successRows},
                    #{row.failedRows}, #{row.status}, #{row.detail}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertLog(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id,
                   direction,
                   biz_type AS bizType,
                   file_name AS fileName,
                   total_rows AS totalRows,
                   success_rows AS successRows,
                   failed_rows AS failedRows,
                   status,
                   detail,
                   create_by AS createBy,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM open_transfer_log
            WHERE deleted = 0
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectRecent(@Param("limit") int limit);
}
