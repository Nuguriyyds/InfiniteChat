package com.wangyutao.offlineservice.service;

import com.wangyutao.offlineservice.model.dto.ReadMessageRequest;

import java.util.Map;

public interface UnreadCountService {

    Map<String, Integer> getUnreadCount(Long userId);

    void incrementUnreadCount(Long userId, String sessionId, String messageId);

    void markAsRead(ReadMessageRequest request);

    void clearUnread(Long userId, String sessionId);
}
