package com.wangyutao.realtimecommunication.redis;


import com.wangyutao.realtimecommunication.websocket.NettySessionManager;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserKickoutMessageListener implements MessageListener {

    private final NettySessionManager sessionManager;
    @Override
    public void onMessage(Message message, byte[] pattern) {
        // 1. 从广播中解析出要被踢下线的 userId (例如："netty:server:888")
        // 注意：这里要看你 AuthenticationService 里发的到底是什么格式的字符串
        // 拿到原始消息体，例如："Nacos:888"
        String messageBody = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("📢 收到 Redis 踢人广播，目标: {}", messageBody);

        // 假设 messageBody 里包含了 userId，比如直接传了 "888" 或者需要你拆分一下
        // 这里以直接收到 userId 为例：
        String targetUserId = extractUserId(messageBody);

        if (targetUserId != null) {
            // 2. 去花名册里查，这个倒霉蛋连在咱们这台机器上吗？
            Channel channel = sessionManager.getChannel(targetUserId);

            if (channel != null) {
                // 3. 抓到人了！直接断开 TCP 连接
                log.warn("💥 正在执行跨服务物理踢出，切断用户 [{}] 的连接！", targetUserId);
                channel.close();
                // sessionManager.remove(channel) 会在 channelInactive 里自动触发，所以这里不用重复写
            } else {
                log.debug("用户 [{}] 不在当前 Netty 节点，忽略该广播。", targetUserId);
            }
        }
    }

    // 辅助方法：根据你实际发送的格式提取 userId
    private String extractUserId(String messageBody) {
        // 如果你的发送代码是: redisTemplate.convertAndSend(TOPIC, "netty_server_" + userId)
        // 那你这里就需要把前缀去掉，提取出真正的 888
        String prefix = "Nacos:"; // 对应你配置里的 ConfigEnum.NETTY_SERVER_HEAD
        if (messageBody.startsWith(prefix)) {
            return messageBody.substring(prefix.length());
        }
        return messageBody;
    }
}
