package com.wangyutao.messaging.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedPacketReceiveMqMessage {

    private Long redPacketId;

    private Long userId;

    /**
     * 抢到的金额，由 executeLocalTransaction 执行 Lua 后写入 Redis pending hash，
     * 消费者从 Redis 读取，避免半消息阶段无法预知金额的问题
     */
    private BigDecimal amount;
}
