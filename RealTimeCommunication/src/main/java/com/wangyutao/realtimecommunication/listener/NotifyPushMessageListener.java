package com.wangyutao.realtimecommunication.listener;

import com.alibaba.fastjson.JSON;
import com.wangyutao.realtimecommunication.model.dto.GatewayPushPacket;
import com.wangyutao.realtimecommunication.websocket.NettySessionManager;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
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
        topic = "IM_NOTIFY",
        consumerGroup = "netty-notify-group-${im.node.id:NODE_1}",
        selectorExpression = "${im.node.id:NODE_1}",
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class NotifyPushMessageListener implements RocketMQListener<String> {

    private final NettySessionManager nettySessionManager;
    private final RocketMQTemplate rocketMQTemplate;

    @Override
    public void onMessage(String mqJsonPayload) {
        try {
            GatewayPushPacket packet = JSON.parseObject(mqJsonPayload, GatewayPushPacket.class);

            List<Long> targetUserIds = packet.getTargetUserIds();
            String pushContent = packet.getWsPayload();

            if (targetUserIds == null || targetUserIds.isEmpty() || pushContent == null) {
                return;
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
                String offlineJson = JSON.toJSONString(offlinePacket);

                rocketMQTemplate.asyncSend("IM_NOTIFY:OFFLINE",
                        MessageBuilder.withPayload(offlineJson).build(),
                        new SendCallback() {
                            @Override
                            public void onSuccess(SendResult sendResult) {
                                log.info("通知离线回流成功, 离线用户数: {}", failedUserIds.size());
                            }

                            @Override
                            public void onException(Throwable e) {
                                log.error("通知离线回流失败, userIds: {}", failedUserIds, e);
                            }
                        }
                );
            }

            log.info("通知推送完成, 目标人数: {}, 成功: {}, 回流离线: {}",
                    targetUserIds.size(), pushSuccessCount, failedUserIds.size());

        } catch (Exception e) {
            log.error("消费 IM_NOTIFY 消息异常，消息已丢弃，消息内容: {}", mqJsonPayload, e);
        }
    }
}
