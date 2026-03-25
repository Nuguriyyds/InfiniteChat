package com.wangyutao.offlineservice.listener;

import com.alibaba.fastjson.JSON;
import com.wangyutao.offlineservice.model.dto.GatewayPushPacket;
import com.wangyutao.offlineservice.model.entity.AppMessage;
import com.wangyutao.offlineservice.service.OfflineMessageService;
import com.wangyutao.offlineservice.service.UnreadCountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "IM_CHAT",
        consumerGroup = "im-offline-message-group",
        selectorExpression = "OFFLINE",
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class OfflineMessageListener implements RocketMQListener<String> {

    private final OfflineMessageService offlineMessageService;
    private final UnreadCountService unreadCountService;

    @Override
    public void onMessage(String messageBody) {
        try {
            GatewayPushPacket packet = JSON.parseObject(messageBody, GatewayPushPacket.class);
            if (packet == null) {
                log.warn("离线消息解析失败: packet 为 null");
                return;
            }

            String wsPayload = packet.getWsPayload();
            AppMessage appMessage = JSON.parseObject(wsPayload, AppMessage.class);
            if (appMessage == null) {
                log.warn("离线消息解析失败: appMessage 为 null");
                return;
            }

            List<Long> targetUserIds = packet.getTargetUserIds();
            if (targetUserIds == null || targetUserIds.isEmpty()) {
                log.warn("离线消息目标用户为空");
                return;
            }

            for (Long receiverId : targetUserIds) {
                offlineMessageService.storeOfflineMessage(receiverId, wsPayload);
                unreadCountService.incrementUnreadCount(
                        receiverId,
                        appMessage.getSessionId(),
                        appMessage.getMessageId()
                );
                log.info("离线消息处理成功, receiverId={}, messageId={}", receiverId, appMessage.getMessageId());
            }

        } catch (Exception e) {
            log.error("处理离线消息失败: {}", messageBody, e);
            throw e;
        }
    }
}
