package com.wangyutao.messaging.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import java.math.BigDecimal;

/**
 * 红包领取明细（用于展示“看大家手气”列表中的每一项）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class RedPacketUser {

    /**
     * 领取者昵称
     */
    private String userName;

    /**
     * 领取者头像
     */
    private String avatar;

    /**
     * 领取时间 (前端直接展示字符串格式)
     */
    private String receiveTime;

    /**
     * 抢到的金额
     */
    private BigDecimal amount;
}