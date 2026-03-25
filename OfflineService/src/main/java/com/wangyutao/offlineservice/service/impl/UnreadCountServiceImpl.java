package com.wangyutao.offlineservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wangyutao.offlineservice.exception.ServiceException;
import com.wangyutao.offlineservice.mapper.UnreadCountMapper;
import com.wangyutao.offlineservice.model.dto.ReadMessageRequest;
import com.wangyutao.offlineservice.model.entity.UnreadCount;
import com.wangyutao.offlineservice.service.UnreadCountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnreadCountServiceImpl implements UnreadCountService {

    private final UnreadCountMapper unreadCountMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String UNREAD_COUNT_KEY_PREFIX = "im:unread:";

    @Override
    public Map<String, Integer> getUnreadCount(Long userId) {
        String key = UNREAD_COUNT_KEY_PREFIX + userId;

        try {
            Map<Object, Object> redisMap = redisTemplate.opsForHash().entries(key);
            if (redisMap != null && !redisMap.isEmpty()) {
                Map<String, Integer> result = new HashMap<>();
                redisMap.forEach((k, v) -> result.put(k.toString(), Integer.parseInt(v.toString())));
                return result;
            }

            QueryWrapper<UnreadCount> wrapper = new QueryWrapper<>();
            wrapper.eq("user_id", userId);
            List<UnreadCount> unreadCounts = unreadCountMapper.selectList(wrapper);

            Map<String, Integer> result = new HashMap<>();
            for (UnreadCount unreadCount : unreadCounts) {
                result.put(unreadCount.getSessionId(), unreadCount.getUnreadCount());
                redisTemplate.opsForHash().put(
                        key,
                        unreadCount.getSessionId(),
                        String.valueOf(unreadCount.getUnreadCount())
                );
            }
            return result;
        } catch (Exception e) {
            log.error("获取未读数失败, userId={}", userId, e);
            return new HashMap<>();
        }
    }

    @Override
    public void incrementUnreadCount(Long userId, String sessionId, String messageId) {
        String key = UNREAD_COUNT_KEY_PREFIX + userId;

        try {
            redisTemplate.opsForHash().increment(key, sessionId, 1);
            unreadCountMapper.incrementUnreadCount(userId, sessionId, messageId, LocalDateTime.now());
            log.debug("未读数 +1, userId={}, sessionId={}", userId, sessionId);
        } catch (Exception e) {
            log.error("增加未读数失败, userId={}, sessionId={}", userId, sessionId, e);
            throw new ServiceException("增加未读数失败", e);
        }
    }

    @Override
    public void markAsRead(ReadMessageRequest request) {
        Long userId = request.getUserId();
        String sessionId = request.getSessionId();
        String key = UNREAD_COUNT_KEY_PREFIX + userId;

        try {
            redisTemplate.opsForHash().delete(key, sessionId);
            unreadCountMapper.clearUnreadCount(userId, sessionId);
            log.info("标记已读成功, userId={}, sessionId={}, lastReadSeq={}",
                    userId, sessionId, request.getLastReadSeq());
        } catch (Exception e) {
            log.error("标记已读失败, userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    @Override
    public void clearUnread(Long userId, String sessionId) {
        String key = UNREAD_COUNT_KEY_PREFIX + userId;

        try {
            redisTemplate.opsForHash().delete(key, sessionId);
            unreadCountMapper.clearUnreadCount(userId, sessionId);
            log.info("清空未读数成功, userId={}, sessionId={}", userId, sessionId);
        } catch (Exception e) {
            log.error("清空未读数失败, userId={}, sessionId={}", userId, sessionId, e);
        }
    }
}
