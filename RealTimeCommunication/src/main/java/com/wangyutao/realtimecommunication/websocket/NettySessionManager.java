package com.wangyutao.realtimecommunication.websocket;


import io.netty.channel.Channel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Getter
@Slf4j
@Component
public class NettySessionManager {
    // 在 NettySessionManager.java 中添加这行
    private final ConcurrentMap<String, Channel> userChannelMap = new ConcurrentHashMap<>();

    /**
     * 1. 用户上线：绑定 UserId 和 Channel
     */
    public void add(String userId, Channel channel) {
        if (userId != null && channel != null) {
            userChannelMap.put(userId, channel);
            log.info("✅ 用户上线登记成功, userId: {}, 当前在线总人数: {}", userId, userChannelMap.size());
        }
    }

    /**
     * 2. 根据 UserId 获取 Channel (用于私聊/系统推送精准找人)
     */
    public Channel getChannel(String userId) {
        if (userId == null) return null;
        return userChannelMap.get(userId);
    }

    /**
     * 3. 根据 UserId 移除映射 (通常用于异地登录挤下线)
     */
    public void remove(String userId) {
        if (userId != null) {
            userChannelMap.remove(userId);
            log.info("⛔ 移除用户会话, userId: {}, 当前在线总人数: {}", userId, userChannelMap.size());
        }
    }

    /**
     * 4. 根据 Channel 移除映射 (核心神技：利用便利贴 O(1) 极速撕下)
     * 完美适用于各种断网、主动退出、心跳超时场景，彻底告别 for 循环遍历！
     */
    public void remove(Channel channel) {
        if (channel == null) return;

        // 从 Channel 身上提取我们在鉴权时贴好的 UserId 便利贴
        String userId = channel.attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();
        if (userId != null) {
            userChannelMap.remove(userId);
            log.info("🔌 连接断开清理成功, userId: {}, 当前在线总人数: {}", userId, userChannelMap.size());
        }
    }

}
