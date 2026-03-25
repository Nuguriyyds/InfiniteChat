package com.wangyutao.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wangyutao.messaging.model.entity.RedPacketReceive;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 红包领取记录表 Mapper 接口
 */
@Mapper
public interface RedPacketReceiveMapper extends BaseMapper<RedPacketReceive> {
    /**
     * 根据红包ID查询领取记录
     *
     * @param redPacketId 红包ID
     * @param page     页码
     * @return 红包领取记录列表
     */
    @Select("SELECT * FROM red_packet_receive WHERE red_packet_id = #{redPacketId} ORDER BY received_at DESC")
    Page<RedPacketReceive> selectByRedPacketId(Page<RedPacketReceive> page, @Param("redPacketId") Long redPacketId);

    @Select("SELECT COUNT(1) FROM red_packet_receive WHERE red_packet_id = #{redPacketId} AND receiver_id = #{userId}")
    int countByRedPacketAndUser(@Param("redPacketId") Long redPacketId, @Param("userId") Long userId);
}