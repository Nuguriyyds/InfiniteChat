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

    @Value("${im.node.id:NODE_1}")
    private String localNodeId;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String messageBody = new String(message.getBody(), StandardCharsets.UTF_8).trim();
            String targetNodeId = null;
            String userIdStr;

            if (messageBody.contains(":") && !messageBody.startsWith("im:route:")) {
                int colonIdx = messageBody.indexOf(":");
                targetNodeId = messageBody.substring(0, colonIdx);
                userIdStr = messageBody.substring(colonIdx + 1);
            } else if (messageBody.startsWith("im:route:")) {
                userIdStr = messageBody.substring("im:route:".length());
            } else {
                userIdStr = messageBody;
            }

            if (targetNodeId != null && !targetNodeId.equals(localNodeId)) {
                log.debug("忽略非本节点踢线广播: localNodeId={}, targetNodeId={}, userId={}", localNodeId, targetNodeId, userIdStr);
                return;
            }

            Long targetUserId = Long.valueOf(userIdStr);
            Channel channel = sessionManager.getChannel(targetUserId);
            if (channel == null) {
                log.debug("踢线广播命中空会话: localNodeId={}, userId={}", localNodeId, targetUserId);
                return;
            }

            if (targetNodeId == null) {
                log.warn("执行广播踢线: localNodeId={}, userId={}", localNodeId, targetUserId);
            } else {
                log.warn("执行精准踢线: localNodeId={}, targetNodeId={}, userId={}", localNodeId, targetNodeId, targetUserId);
            }
            channel.close();
        } catch (NumberFormatException e) {
            log.error("踢线广播解析失败: userId 非法", e);
        } catch (Exception e) {
            log.error("处理踢线广播失败", e);
        }
    }
}
