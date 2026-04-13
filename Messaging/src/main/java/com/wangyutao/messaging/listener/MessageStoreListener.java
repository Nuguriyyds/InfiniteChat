package com.wangyutao.messaging.listener;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.IService;
import com.wangyutao.messaging.model.entity.AppMessage;
import com.wangyutao.messaging.model.entity.Message;
import com.wangyutao.messaging.model.enums.SessionType;
import com.wangyutao.realtimecommunication.model.dto.GatewayPushPacket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "IM_MSG_STORE",
        consumerGroup = "im-message-store-group",
        selectorExpression = "*",
        consumeMode = ConsumeMode.CONCURRENTLY, // 改为并发消费：落库操作彼此独立，无需强顺序
        consumeThreadNumber = 20                // 增加消费线程数，提升并发落库吞吐
)
public class MessageStoreListener implements RocketMQListener<String> {

    private final IService<Message> messageService;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;

    @Override
    public void onMessage(String messageBody) {
        try {
            AppMessage appMessage = JSON.parseObject(messageBody, AppMessage.class);
            if (appMessage == null) {
                log.warn("IM_MSG_STORE parse failed: appMessage is null");
                return;
            }

            storeMessage(appMessage);

            if (appMessage.getSessionType() == SessionType.SINGLE.getValue()) {
                handleSingleChatPush(appMessage);
            } else {
                handleGroupChatPush(appMessage);
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("IM_MSG_STORE duplicate message ignored, body={}", messageBody);
        } catch (Exception e) {
            log.error("IM_MSG_STORE consume failed, body={}", messageBody, e);
            throw e;
        }
    }

    private void handleSingleChatPush(AppMessage appMessage) {
        List<Long> receiveUserIds = appMessage.getReceiveUserIds();
        if (receiveUserIds == null || receiveUserIds.isEmpty()) {
            return;
        }

        Long receiveUserId = receiveUserIds.get(0);
        String targetNodeId = redisTemplate.opsForValue().get("im:route:" + receiveUserId);
        String mqTag = (targetNodeId == null || targetNodeId.isEmpty()) ? "OFFLINE" : targetNodeId;

        GatewayPushPacket packet = new GatewayPushPacket(
                Collections.singletonList(receiveUserId),
                JSON.toJSONString(appMessage)
        );

        org.springframework.messaging.Message<String> mqMessage = MessageBuilder
                .withPayload(JSON.toJSONString(packet))
                .setHeader("KEYS", appMessage.getMessageId())
                .build();

        SendResult sendResult = rocketMQTemplate.syncSendOrderly(
                "IM_CHAT:" + mqTag,
                mqMessage,
                appMessage.getSessionId()
        );
        log.info("single chat forward done, messageId={}, mqTag={}, sendStatus={}",
                appMessage.getMessageId(), mqTag, sendResult != null ? sendResult.getSendStatus() : null);
    }

    private void handleGroupChatPush(AppMessage appMessage) {
        List<Long> memberIds = appMessage.getReceiveUserIds();
        if (memberIds == null || memberIds.isEmpty()) {
            return;
        }

        List<String> routeKeys = memberIds.stream()
                .map(id -> "im:route:" + id)
                .collect(Collectors.toList());
        List<String> targetNodes = redisTemplate.opsForValue().multiGet(routeKeys);
        if (targetNodes == null) {
            return;
        }

        Map<String, List<Long>> nodeUserMap = new HashMap<>();
        List<Long> offlineUserIds = new ArrayList<>();
        for (int i = 0; i < memberIds.size(); i++) {
            String nodeId = targetNodes.get(i);
            if (nodeId != null && !nodeId.isEmpty() && !"OFFLINE".equals(nodeId)) {
                nodeUserMap.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(memberIds.get(i));
            } else {
                offlineUserIds.add(memberIds.get(i));
            }
        }

        appMessage.setReceiveUserIds(null);
        String wsPayload = JSON.toJSONString(appMessage);

        for (Map.Entry<String, List<Long>> entry : nodeUserMap.entrySet()) {
            GatewayPushPacket packet = new GatewayPushPacket(entry.getValue(), wsPayload);
            org.springframework.messaging.Message<String> mqMessage = MessageBuilder
                    .withPayload(JSON.toJSONString(packet))
                    .setHeader("KEYS", appMessage.getMessageId())
                    .build();
            rocketMQTemplate.syncSendOrderly(
                    "IM_CHAT:" + entry.getKey(),
                    mqMessage,
                    appMessage.getSessionId()
            );
        }

        if (!offlineUserIds.isEmpty()) {
            GatewayPushPacket offlinePacket = new GatewayPushPacket(offlineUserIds, wsPayload);
            org.springframework.messaging.Message<String> offlineMqMessage = MessageBuilder
                    .withPayload(JSON.toJSONString(offlinePacket))
                    .setHeader("KEYS", appMessage.getMessageId())
                    .build();
            rocketMQTemplate.syncSendOrderly(
                    "IM_CHAT:OFFLINE",
                    offlineMqMessage,
                    appMessage.getSessionId()
            );
        }
    }

    private void storeMessage(AppMessage appMessage) {
        Message entity = new Message();
        entity.setMessageId(appMessage.getMessageId());
        entity.setClientMsgId(appMessage.getClientMsgId());
        entity.setSessionId(appMessage.getSessionId());
        entity.setSeq(appMessage.getSeq());
        entity.setSenderId(appMessage.getSendUserId());
        entity.setSessionType(appMessage.getSessionType());
        entity.setMessageType(appMessage.getType());
        entity.setReplyId(null);
        entity.setStatus(0);
        entity.setContent(appMessage.getBody() == null ? null : JSON.toJSONString(appMessage.getBody()));
        Date now = new Date();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);

        boolean success = messageService.save(entity);
        if (!success) {
            log.error("IM_MSG_STORE save failed, messageId={}", entity.getMessageId());
            throw new RuntimeException("IM_MSG_STORE save failed");
        }
        log.debug("IM_MSG_STORE save success, messageId={}, sessionId={}", entity.getMessageId(), entity.getSessionId());
    }
}