package com.wangyutao.messaging.model.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
public class ResponseMsgVo {

    private String clientMsgId;

    private String sessionId; // 🌟 如果 sessionId 也是纯数字 Long，记得加 @JsonSerialize

    private Integer sessionType;

    private Integer type;

    private Long senderId;

    private Long seq;

    /**
     * 🌟 修改：Long -> String
     * 理由：因为咱们的消息 ID 携带了 "msg_" 前缀，必须用 String 承载。
     */
    private String messageId;

    private Object body;

    private Integer status;

    private Date createdAt;
}
