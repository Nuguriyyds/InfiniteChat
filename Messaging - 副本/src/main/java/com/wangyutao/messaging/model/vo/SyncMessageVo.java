package com.wangyutao.messaging.model.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SyncMessageVo {

    private String messageId;
    private String sessionId;
    private Long seq;
    private Long senderId;
    private Integer messageType;
    private String content;
    private String createdAt;
    /**
     * 消息状态: 0正常, 1撤回, 2删除
     * -1 表示墓碑消息（该 seq 对应的消息在服务端不存在，客户端无需再等待）
     */
    private Integer status;
    private Boolean isTombstone;
}
