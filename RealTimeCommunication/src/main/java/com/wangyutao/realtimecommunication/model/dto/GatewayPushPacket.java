package com.wangyutao.realtimecommunication.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 🌟 Netty 网关下发标准件 (MQ 传输专用)
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GatewayPushPacket {
    // 目标用户的 ID 列表 (Netty 就靠这个去 Map 里找 Channel)
    private List<Long> targetUserIds;
    // 已经序列化好的、准备发给前端的 JSON 字符串
    private String wsPayload;
}