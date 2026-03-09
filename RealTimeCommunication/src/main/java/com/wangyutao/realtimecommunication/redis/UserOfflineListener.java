package com.wangyutao.realtimecommunication.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserOfflineListener implements MessageListener {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        // 1. 获取刚刚断网/心跳超时的 userId
        String userId = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("📢 [全网广播接收] 用户 [{}] 已掉线，准备清理路由缓存", userId);

        // 2. 精准删除该用户在 Redis 里的全局路由映射
        // 这里的 "Nacos:" 前缀需要和你在 Auth 服务里写入时保持绝对一致
        String routingKey = "Nacos:" + userId;

        Boolean deleted = redisTemplate.delete(routingKey);

        if (Boolean.TRUE.equals(deleted)) {
            log.info("✅ 成功清理用户 [{}] 的全局路由缓存", userId);
        } else {
            log.debug("⚠️ 用户 [{}] 的路由缓存不存在或已被清理", userId);
        }
    }
}