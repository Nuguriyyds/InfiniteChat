package com.wangyutao.offlineservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.offlineservice.model.entity.OfflineMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OfflineMessageMapper extends BaseMapper<OfflineMessage> {

    @Select("SELECT * FROM offline_message " +
            "WHERE receiver_id = #{receiverId} " +
            "AND session_id = #{sessionId} " +
            "AND seq > #{lastSeq} " +
            "AND status = 0 " +
            "ORDER BY seq ASC " +
            "LIMIT #{limit}")
    List<OfflineMessage> selectBySessionAndSeq(
            @Param("receiverId") Long receiverId,
            @Param("sessionId") String sessionId,
            @Param("lastSeq") Long lastSeq,
            @Param("limit") Integer limit
    );

    @Select("SELECT COUNT(*) FROM offline_message " +
            "WHERE receiver_id = #{receiverId} " +
            "AND status = 0")
    Long countByReceiver(@Param("receiverId") Long receiverId);

    @Select("SELECT COUNT(*) FROM offline_message " +
            "WHERE receiver_id = #{receiverId} " +
            "AND message_id = #{messageId}")
    Long countByReceiverAndMessageId(
            @Param("receiverId") Long receiverId,
            @Param("messageId") String messageId
    );
}
