package com.wangyutao.messaging.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor // 🌟 补上无参构造，方便反序列化
@AllArgsConstructor
@Accessors(chain = true)
public class RedPacketResponse {

    private List<RedPacketUser> list;
    private String senderName;
    private String senderAvatar;
    private String redPacketWrapperText;
    private Integer redPacketType;
    private BigDecimal totalAmount;
    private Integer totalCount;
    private BigDecimal remainingAmount;
    private Integer remainingCount;

    /**
     * 状态：0正常,1未领取完，2已领取完，3已过期
     */
    private Integer status;

    public RedPacketResponse(Integer status) {
        this.status = status;
    }
}
