package com.wangyutao.offlineservice.controller;

import com.wangyutao.offlineservice.common.Result;
import com.wangyutao.offlineservice.common.ResultGenerator;
import com.wangyutao.offlineservice.model.dto.PullOfflineMessageRequest;
import com.wangyutao.offlineservice.model.dto.PullOfflineMessageResponse;
import com.wangyutao.offlineservice.model.dto.ReadMessageRequest;
import com.wangyutao.offlineservice.service.OfflineMessageService;
import com.wangyutao.offlineservice.service.UnreadCountService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/offline")
@RequiredArgsConstructor
public class OfflineMessageController {

    private final OfflineMessageService offlineMessageService;
    private final UnreadCountService unreadCountService;
    private final StringRedisTemplate redisTemplate;

    @PostMapping("/pull")
    public Result<PullOfflineMessageResponse> pullOfflineMessages(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestBody PullOfflineMessageRequest request) {

        request.setUserId(userId);
        return ResultGenerator.genSuccessResult(
            offlineMessageService.pullOfflineMessages(request)
        );
    }

    @GetMapping("/unread")
    public Result<Map<String, Integer>> getUnreadCount(
            @RequestHeader(value = "X-User-Id", required = true) Long userId) {

        return ResultGenerator.genSuccessResult(
            unreadCountService.getUnreadCount(userId)
        );
    }

    @PostMapping("/read")
    public Result<Void> markAsRead(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestBody ReadMessageRequest request) {

        request.setUserId(userId);
        unreadCountService.markAsRead(request);
        return ResultGenerator.genSuccessResult(null);
    }

    @PostMapping("/clear/{sessionId}")
    public Result<Void> clearUnread(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PathVariable String sessionId) {

        unreadCountService.clearUnread(userId, sessionId);
        return ResultGenerator.genSuccessResult(null);
    }

    @PostMapping("/notifications")
    public Result<List<String>> pullOfflineNotifications(
            @RequestHeader(value = "X-User-Id", required = true) Long userId) {

        String key = "im:offline:notify:" + userId;
        List<String> notifications = redisTemplate.opsForList().range(key, 0, -1);
        if (notifications != null && !notifications.isEmpty()) {
            redisTemplate.delete(key);
        }
        return ResultGenerator.genSuccessResult(
                notifications != null ? notifications : Collections.emptyList()
        );
    }
}
