package com.wangyutao.realtimecommunication.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AckData {

    private String sessionId;

    private Long seq;

    private String messageId;

    /**
     * Backward-compatible alias used by older WS scripts.
     */
    private String msgUuid;
}
