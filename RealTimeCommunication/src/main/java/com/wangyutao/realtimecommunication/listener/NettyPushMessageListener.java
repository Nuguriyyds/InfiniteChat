package com.wangyutao.realtimecommunication.listener;

import com.alibaba.fastjson.JSON;
import com.wangyutao.realtimecommunication.model.dto.GatewayPushPacket;
import com.wangyutao.realtimecommunication.websocket.NettySessionManager;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "IM_CHAT",
        consumerGroup = "netty-push-group-${im.node.id:NODE_1}",
        selectorExpression = "${im.node.id:NODE_1}",
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class NettyPushMessageListener implements RocketMQListener<String> {

    private static final long OFFLINE_SEND_TIMEOUT_MS = 3000L;

    private final NettySessionManager nettySessionManager;
    private final RocketMQTemplate rocketMQTemplate;

    @Override
    public void onMessage(String mqJsonPayload) {
        try {
            GatewayPushPacket packet = JSON.parseObject(mqJsonPayload, GatewayPushPacket.class);
            if (packet == null) {
                throw new IllegalArgumentException("GatewayPushPacket is null");
            }

            List<Long> targetUserIds = packet.getTargetUserIds();
            String pushContent = packet.getWsPayload();
            if (targetUserIds == null || targetUserIds.isEmpty() || pushContent == null) {
                throw new IllegalArgumentException("push packet targetUserIds/wsPayload is empty");
            }

            int pushSuccessCount = 0;
            List<Long> failedUserIds = new ArrayList<>();
            for (Long userId : targetUserIds) {
                Channel channel = nettySessionManager.getChannel(userId);
                if (channel != null && channel.isActive()) {
                    channel.writeAndFlush(new TextWebSocketFrame(pushContent));
                    pushSuccessCount++;
                } else {
                    failedUserIds.add(userId);
                }
            }

            if (!failedUserIds.isEmpty()) {
                GatewayPushPacket offlinePacket = new GatewayPushPacket(failedUserIds, pushContent);
                SendResult sendResult = rocketMQTemplate.syncSend(
                        "IM_CHAT:OFFLINE",
                        MessageBuilder.withPayload(JSON.toJSONString(offlinePacket)).build(),
                        OFFLINE_SEND_TIMEOUT_MS
                );
                log.info("在线推送失败回流离线成功, offlineUserCount={}, sendStatus={}",
                        failedUserIds.size(), sendResult != null ? sendResult.getSendStatus() : null);
            }

            log.info("MQ 消费下发完成, targetCount={}, pushSuccessCount={}, offlineFallbackCount={}",
                    targetUserIds.size(), pushSuccessCount, failedUserIds.size());
        } catch (Exception e) {
            log.error("Netty 消费 MQ 消息异常, payload={}", mqJsonPayload, e);
            throw new RuntimeException("Netty push consume failed", e);
        }
    }
}
