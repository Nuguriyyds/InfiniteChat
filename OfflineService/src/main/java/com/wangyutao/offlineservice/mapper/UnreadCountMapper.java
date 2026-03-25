package com.wangyutao.offlineservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.offlineservice.model.entity.UnreadCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UnreadCountMapper extends BaseMapper<UnreadCount> {

    @Update("INSERT INTO unread_count (user_id, session_id, unread_count, last_message_id, last_message_time, updated_at) " +
            "VALUES (#{userId}, #{sessionId}, 1, #{messageId}, #{messageTime}, NOW()) " +
            "ON DUPLICATE KEY UPDATE " +
            "unread_count = unread_count + 1, " +
            "last_message_id = #{messageId}, " +
            "last_message_time = #{messageTime}, " +
            "updated_at = NOW()")
    int incrementUnreadCount(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId,
            @Param("messageId") String messageId,
            @Param("messageTime") java.time.LocalDateTime messageTime
    );

    @Update("UPDATE unread_count " +
            "SET unread_count = GREATEST(unread_count - #{count}, 0), " +
            "updated_at = NOW() " +
            "WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int decrementUnreadCount(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId,
            @Param("count") Integer count
    );

    @Update("UPDATE unread_count " +
            "SET unread_count = 0, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int clearUnreadCount(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId
    );
}
