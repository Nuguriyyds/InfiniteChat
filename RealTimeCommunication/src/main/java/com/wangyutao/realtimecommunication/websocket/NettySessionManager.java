package com.wangyutao.realtimecommunication.websocket;

import io.netty.channel.Channel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Getter
@Slf4j
@Component
public class NettySessionManager {

    private final ConcurrentMap<Long, Channel> userChannelMap = new ConcurrentHashMap<>();

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    // 🌟 1. 注入当前节点的动态 ID（必须和 Handler 里注册路由时用的一模一样）
    @Value("${im.node.id:NODE_1}")
    private String localNodeId;

    /**
     * 1. 用户上线：绑定 UserId 和 Channel
     */
    public void add(Long userId, Channel channel) {
        if (userId != null && channel != null) {
            userChannelMap.put(userId, channel);
            log.info("✅ 用户上线登记成功, userId: {}, 当前在线总人数: {}", userId, userChannelMap.size());
        }
    }

    /**
     * 2. 根据 UserId 获取 Channel (用于私聊/系统推送精准找人)
     */
    public Channel getChannel(Long userId) {
        if (userId == null) return null;
        return userChannelMap.get(userId);
    }

    /**
     * 3. 根据 UserId 移除映射 (通常只清理本地内存)
     */
    public void remove(Long userId) {
        if (userId != null) {
            userChannelMap.remove(userId);
            log.info("⛔ 移除用户本地会话, userId: {}, 当前在线总人数: {}", userId, userChannelMap.size());
        }
    }

    /**
     * 4. 🌟 终极改造：根据 Channel 移除映射（防误杀版）
     */
    public void remove(Channel channel) {
        if (channel == null) return;

        Long userId = channel.attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();
        if (userId != null) {
            // CAS remove: only delete if the map still holds THIS exact channel instance
            boolean removed = userChannelMap.remove(userId, channel);

            if (removed) {
                // Local mapping was ours, now check if we should also clean the global route
                String routeKey = "im:route:" + userId;
                String registeredNode = redisTemplate.opsForValue().get(routeKey);

                if (localNodeId.equals(registeredNode)) {
                    redisTemplate.delete(routeKey);
                    log.info("连接断开，全局路由清理成功, userId: {}, 当前在线总人数: {}", userId, userChannelMap.size());
                } else {
                    log.info("连接断开，全局路由已被其他节点接管，跳过清理, userId: {}, 接管节点: {}", userId, registeredNode);
                }
            } else {
                log.info("连接断开，但本地映射已被新连接覆盖，跳过清理, userId: {}", userId);
            }
        }
    }
}