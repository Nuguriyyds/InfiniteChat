package com.wangyutao.offlineservice.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("unread_count")
public class UnreadCount {

    private Long userId;
    private String sessionId;
    private Integer unreadCount;
    private String lastMessageId;
    private LocalDateTime lastMessageTime;
    private LocalDateTime updatedAt;
}
