package com.wangyutao.messaging.listener;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.IService;
import com.wangyutao.messaging.model.entity.AppMessage;
import com.wangyutao.messaging.model.entity.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "IM_CHAT",
        consumerGroup = "im-message-store-group",
        selectorExpression = "*"
)
public class MessageStoreListener implements RocketMQListener<String> {

    private final IService<Message> messageService;

    @Override
    public void onMessage(String messageBody) {
        try {
            com.wangyutao.realtimecommunication.model.dto.GatewayPushPacket packet =
                    JSON.parseObject(messageBody, com.wangyutao.realtimecommunication.model.dto.GatewayPushPacket.class);
            if (packet == null) {
                log.warn("IM 消息解析失败：packet 为 null");
                return;
            }
            String wsPayload = packet.getWsPayload();
            AppMessage appMessage = JSON.parseObject(wsPayload, AppMessage.class);
            if (appMessage == null) {
                log.warn("IM 消息解析失败：appMessage 为 null");
                return;
            }

            storeMessage(appMessage);

        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("IM 消息已存在，跳过重复落库, body={}", messageBody);
        } catch (Exception e) {
            log.error("IM 消息处理异常, body={}", messageBody, e);
            throw e;
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
            log.error("IM 消息落库失败, messageId={}", entity.getMessageId());
            throw new RuntimeException("IM 消息落库失败");
        }
        log.info("IM 消息落库成功, messageId={}, sessionId={}", entity.getMessageId(), entity.getSessionId());
    }
}
