package com.wangyutao.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.messaging.model.entity.BalanceLog;
import com.wangyutao.messaging.model.entity.RedPacket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface RedPacketMapper extends BaseMapper<RedPacket> {
    @Update("UPDATE red_packet SET remaining_amount = #{amount}, remaining_count = #{count}, version = version + 1 " +
                   "WHERE red_packet_id = #{id} AND version = #{version}")
    int updateRedPacketWithVersion(@Param("id") Long id,
                                   @Param("amount") BigDecimal amount,
                                   @Param("count") Integer count,
                                   @Param("version") Integer version);
}
