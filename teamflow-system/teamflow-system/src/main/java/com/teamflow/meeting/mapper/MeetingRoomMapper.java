package com.teamflow.meeting.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M06 会议室表（{@code meeting_room}）访问层。
 */
@Mapper
public interface MeetingRoomMapper {

    @Select("""
            <script>
            SELECT r.id,
                   r.room_name AS roomName,
                   r.room_code AS roomCode,
                   r.location,
                   r.capacity,
                   r.equipment,
                   r.status,
                   r.sort,
                   r.remark,
                   DATE_FORMAT(r.create_time, '%Y-%m-%d %H:%i') AS createTime,
                   DATE_FORMAT(r.update_time, '%Y-%m-%d %H:%i') AS updateTime
            FROM meeting_room r
            WHERE r.deleted = 0
              AND (#{keyword} IS NULL OR #{keyword} = ''
                   OR r.room_name LIKE CONCAT('%', #{keyword}, '%')
                   OR r.room_code LIKE CONCAT('%', #{keyword}, '%')
                   OR r.location LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{status} IS NULL OR #{status} = '' OR r.status = #{status})
              AND (#{minCapacity} IS NULL OR r.capacity &gt;= #{minCapacity})
            ORDER BY r.sort, r.id
            </script>
            """)
    List<Map<String, Object>> selectRooms(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("minCapacity") Integer minCapacity
    );

    @Select("""
            SELECT id, room_name AS roomName, room_code AS roomCode, location, capacity, equipment,
                   status, sort, remark
            FROM meeting_room
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Map<String, Object> selectRoomById(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM meeting_room
            WHERE room_code = #{roomCode} AND deleted = 0
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    long countRoomCode(@Param("roomCode") String roomCode, @Param("excludeId") Long excludeId);

    /** 预定前锁定会议室行，串行化同一会议室的并发预定（R1）。 */
    @Select("SELECT id FROM meeting_room WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Long lockRoom(@Param("id") Long id);

    @Insert("""
            INSERT INTO meeting_room (room_name, room_code, location, capacity, equipment, status, sort,
                                      remark, create_by, update_by)
            VALUES (#{row.roomName}, #{row.roomCode}, #{row.location}, #{row.capacity}, #{row.equipment},
                    #{row.status}, #{row.sort}, #{row.remark}, #{operator}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertRoom(@Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_room
            SET room_name = #{row.roomName},
                location = #{row.location},
                capacity = #{row.capacity},
                equipment = #{row.equipment},
                status = #{row.status},
                sort = #{row.sort},
                remark = #{row.remark},
                update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateRoom(@Param("id") Long id, @Param("row") Map<String, Object> row, @Param("operator") String operator);

    @Update("""
            UPDATE meeting_room
            SET deleted = 1, update_by = #{operator}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDeleteRoom(@Param("id") Long id, @Param("operator") String operator);
}
