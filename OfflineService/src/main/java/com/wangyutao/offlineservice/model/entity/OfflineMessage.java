package com.wangyutao.offlineservice.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("offline_message")
public class OfflineMessage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private Long receiverId;
    private String sessionId;
    private Long seq;
    private Integer messageType;
    private String content;
    private Long senderId;
    private String senderName;
    private String senderAvatar;
    private LocalDateTime createdAt;
    private LocalDateTime expireAt;
    private Integer status;
}
