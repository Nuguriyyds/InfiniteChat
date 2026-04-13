package com.wangyutao.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.messaging.model.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
* @author yutao
* @description 针对表【im_message(IM核心聊天消息表)】的数据库操作Mapper
* @createDate 2026-03-10 11:36:08
* @Entity generator.domain.ImMessage
*/
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    @Select("SELECT * FROM im_message " +
            "WHERE session_id = #{sessionId} " +
            "AND seq > #{beginSeq} " +
            "AND seq <= #{endSeq} " +
            "AND status = 0 " +
            "ORDER BY seq ASC")
    List<Message> selectBySeqRange(
            @Param("sessionId") String sessionId,
            @Param("beginSeq") Long beginSeq,
            @Param("endSeq") Long endSeq
    );
}




