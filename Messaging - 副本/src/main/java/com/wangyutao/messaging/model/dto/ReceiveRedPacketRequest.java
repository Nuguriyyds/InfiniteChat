package com.wangyutao.messaging.model.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ReceiveRedPacketRequest {
    // 提醒：虽然这里有 userId，但出于安全考虑，
    // 我们会在 Controller 层强制用 Header 里的 Token 解析出的 ID 覆盖它！
    private Long userId;
    private Long redPacketId;
}
