package com.wangyutao.offlineservice.model.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

@Data
@Accessors(chain = true)
public class AppMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String clientMsgId;
    private String messageId;
    private Long seq;
    private String sessionId;
    private Integer sessionType;
    private Integer type;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sendUserId;

    private List<Long> receiveUserIds;
    private String userName;
    private String avatar;
    private String sessionName;
    private String sessionAvatar;
    private String createdAt;
    private Object body;
}
