package com.wangyutao.realtimecommunication.redis;

import com.wangyutao.realtimecommunication.websocket.NettySessionManager;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserKickoutMessageListener implements MessageListener {

    private final NettySessionManager sessionManager;

    @Value("${netty.node-id:NODE_1}")
    private String localNodeId;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String messageBody = new String(message.getBody(), StandardCharsets.UTF_8).trim();
            log.info("收到 Redis 踢人广播，原始报文: {}", messageBody);

            String targetNodeId = null;
            String userIdStr;

            // 新格式: "旧节点ID:用户ID"，旧格式兼容: 纯数字或 "im:route:数字"
            if (messageBody.contains(":") && !messageBody.startsWith("im:route:")) {
                int colonIdx = messageBody.indexOf(":");
                targetNodeId = messageBody.substring(0, colonIdx);
                userIdStr = messageBody.substring(colonIdx + 1);
            } else if (messageBody.startsWith("im:route:")) {
                userIdStr = messageBody.substring("im:route:".length());
            } else {
                userIdStr = messageBody;
            }

            // 如果广播明确指明了要踢的目标节点，且本节点不是那个目标节点，才忽略
            if (targetNodeId != null && !targetNodeId.equals(localNodeId)) {
                log.debug("踢人广播目标是 [{}], 本节点是 [{}], 无需处理，直接忽略", targetNodeId, localNodeId);
                return;
            }

            Long targetUserId = Long.valueOf(userIdStr);
            Channel channel = sessionManager.getChannel(targetUserId);

            if (channel != null && targetNodeId != null) {
                log.warn("正在执行跨服务物理踢出，切断用户 [{}] 的连接", targetUserId);
                channel.close();
            } else {
                log.debug("目标用户 [{}] 不在当前 Netty 节点，忽略该广播", targetUserId);
            }

        } catch (NumberFormatException e) {
            log.error("踢人广播解析异常：userId 格式非法", e);
        } catch (Exception e) {
            log.error("处理踢人广播时发生未知异常", e);
        }
    }
}