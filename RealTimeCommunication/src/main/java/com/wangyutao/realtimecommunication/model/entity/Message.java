package com.wangyutao.realtimecommunication.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class Message {

    protected String sessionId;

    protected List<Long> receiveUserIds;

    protected String sendUserId;

    protected String avatar;

    protected String userName;

    protected Integer type;

    protected String messageId;

    protected Integer sessionType;

    protected String sessionName;

    protected String sessionAvatar;

    protected String createdAt;

    protected Object body;
}