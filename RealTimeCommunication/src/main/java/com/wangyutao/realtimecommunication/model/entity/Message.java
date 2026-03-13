package com.wangyutao.realtimecommunication.model.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class Message {

    private String sessionId;

    private List<Long> receiveUserIds; // 🌟 这个字段在群聊广播时很有用

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sendUserId; // 🌟 回归 Long

    private String avatar;

    private String userName;

    private Integer type;

    private String messageId; // 🌟 保持 String，因为带 msg_ 前缀

    private Integer sessionType;

    private String sessionName;

    private String sessionAvatar;

    private String createdAt;

    private Object body; // 具体的文本、图片链接等
}