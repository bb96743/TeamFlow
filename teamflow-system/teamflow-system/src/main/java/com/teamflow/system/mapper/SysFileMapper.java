package com.teamflow.system.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/**
 * 统一文件表 {@code sys_file} 访问层（G-1）。
 *
 * <p>V11 扩展了 {@code biz_type}/{@code biz_id}/{@code ref_count}/{@code storage_provider}；
 * 文件内容存本地磁盘（{@code teamflow.file.storage-path}），本表只存元数据。</p>
 */
@Mapper
public interface SysFileMapper {

    @Insert("""
            INSERT INTO sys_file (original_name, storage_name, url, content_type, size,
                                  biz_type, biz_id, ref_count, storage_provider, create_by, update_by)
            VALUES (#{row.originalName}, #{row.storageName}, #{row.url}, #{row.contentType}, #{row.size},
                    #{row.bizType}, #{row.bizId}, #{row.refCount}, #{row.storageProvider}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertFile(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id,
                   original_name AS originalName,
                   storage_name AS storageName,
                   url,
                   content_type AS contentType,
                   size,
                   biz_type AS bizType,
                   biz_id AS bizId,
                   ref_count AS refCount,
                   storage_provider AS storageProvider,
                   create_by AS createBy,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM sys_file
            WHERE id = #{id}
            """)
    Map<String, Object> selectFile(@Param("id") Long id);

    /** 上传拿到自增 ID 后回填统一下载地址（避免静态目录直出）。 */
    @Update("UPDATE sys_file SET url = #{url} WHERE id = #{id}")
    int updateUrl(@Param("id") Long id, @Param("url") String url);

    /** 引用 +1（业务模块关联附件时调用）。 */
    @Update("UPDATE sys_file SET ref_count = ref_count + 1, update_by = #{operator} WHERE id = #{id}")
    int incrementRef(@Param("id") Long id, @Param("operator") String operator);

    /** 引用 -1（下限 0，幂等）。 */
    @Update("""
            UPDATE sys_file
            SET ref_count = GREATEST(ref_count - 1, 0), update_by = #{operator}
            WHERE id = #{id} AND ref_count > 0
            """)
    int decrementRef(@Param("id") Long id, @Param("operator") String operator);

    /** 仅当引用计数归零时删除元数据（并发安全：条件更新，返回 0 表示已被引用或不存在）。 */
    @Delete("DELETE FROM sys_file WHERE id = #{id} AND ref_count = 0")
    int deleteIfUnreferenced(@Param("id") Long id);
}
