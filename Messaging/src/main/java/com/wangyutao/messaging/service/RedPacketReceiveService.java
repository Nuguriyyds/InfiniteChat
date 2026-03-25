package com.wangyutao.messaging.service;

import com.wangyutao.messaging.model.dto.ReceiveRedPacketRequest;
import com.wangyutao.messaging.model.dto.ReceiveRedPacketResponse;
import com.wangyutao.messaging.model.entity.RedPacketReceive;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.stereotype.Service;

/**
* @author yutao
* @description 针对表【red_packet_receive(红包领取记录表)】的数据库操作Service
* @createDate 2026-03-11 08:59:49
*/


public interface RedPacketReceiveService extends IService<RedPacketReceive> {
    ReceiveRedPacketResponse receiveRedPacket(ReceiveRedPacketRequest request);

    /**
     * 落库：扣红包库存 + 写领取记录 + 加用户余额 + 写流水，由 Consumer 在事务内调用
     */
    void doSaveReceiveRecord(Long redPacketId, Long userId, java.math.BigDecimal amount);
}
