package com.wangyutao.messaging.model.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class SendMsgRequest implements Serializable {

    private String clientMsgId;

    private String sessionId;

    private Long sendUserId;

    private Integer sessionType;

    private Integer type;

    private Long receiveUserId;

    private Object body;

    private String replyId;


}
