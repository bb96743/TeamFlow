package com.teamflow.personal.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M10 待办处理日志表（{@code todo_handle_log}）访问层。
 *
 * <p>R3 的可审计证据：每次在收件箱处理都记一条（关闭/拒绝/失败），失败留在原事务之外，
 * 即使外层回滚也能看到「为什么没关」。只在服务层使用，不直接对外暴露。</p>
 */
@Mapper
public interface TodoHandleLogMapper {

    @Insert("""
            INSERT INTO todo_handle_log (todo_id, biz_type, biz_id, user_id, action_type, action, result,
                                         handler, reason, cost_ms, create_by, update_by)
            VALUES (#{row.todoId}, #{row.bizType}, #{row.bizId}, #{row.userId}, #{row.actionType}, #{row.action},
                    #{row.result}, #{row.handler}, #{row.reason}, #{row.costMs}, #{operator}, #{operator})
            """)
    int insertLog(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Select("""
            SELECT id, todo_id AS todoId, biz_type AS bizType, biz_id AS bizId, user_id AS userId,
                   action_type AS actionType, action, result, handler, reason, cost_ms AS costMs,
                   DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS createTime
            FROM todo_handle_log
            WHERE todo_id = #{todoId} AND deleted = 0
            ORDER BY id
            """)
    List<Map<String, Object>> selectByTodoId(@Param("todoId") Long todoId);

    @Select("""
            SELECT COUNT(*) FROM todo_handle_log
            WHERE todo_id = #{todoId} AND result = #{result} AND deleted = 0
            """)
    int countByTodoIdAndResult(@Param("todoId") Long todoId, @Param("result") String result);
}
