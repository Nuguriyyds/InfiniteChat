package com.wangyutao.messaging.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import java.math.BigDecimal;

@Data
@NoArgsConstructor // 🌟 加上无参构造，序列化/反序列化万无一失
@AllArgsConstructor
@Accessors(chain = true)
public class ReceiveRedPacketResponse {

    /**
     * 抢到的金额（如果没抢到就是 null）
     */
    private BigDecimal receivedAmount;

    /**
     * 状态：0-抢成功，1-未领取完，2-已领完（手慢无），3-已过期
     */
    private Integer status;
}