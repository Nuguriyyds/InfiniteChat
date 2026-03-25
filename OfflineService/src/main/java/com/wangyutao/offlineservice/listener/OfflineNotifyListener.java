package com.wangyutao.offlineservice.listener;

import com.alibaba.fastjson.JSON;
import com.wangyutao.offlineservice.model.dto.GatewayPushPacket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "IM_NOTIFY",
        consumerGroup = "im-offline-notify-group",
        selectorExpression = "OFFLINE",
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class OfflineNotifyListener implements RocketMQListener<String> {

    private final StringRedisTemplate redisTemplate;

    private static final String NOTIFY_KEY_PREFIX = "im:offline:notify:";
    private static final long NOTIFY_EXPIRE_DAYS = 7;
    private static final long MAX_NOTIFY_PER_USER = 200;

    @Override
    public void onMessage(String messageBody) {
        try {
            GatewayPushPacket packet = JSON.parseObject(messageBody, GatewayPushPacket.class);
            if (packet == null || packet.getTargetUserIds() == null || packet.getTargetUserIds().isEmpty()) {
                log.warn("离线通知解析失败或目标用户为空: {}", messageBody);
                return;
            }

            String wsPayload = packet.getWsPayload();

            for (Long userId : packet.getTargetUserIds()) {
                String key = NOTIFY_KEY_PREFIX + userId;
                redisTemplate.opsForList().rightPush(key, wsPayload);
                redisTemplate.opsForList().trim(key, -MAX_NOTIFY_PER_USER, -1);
                redisTemplate.expire(key, NOTIFY_EXPIRE_DAYS, TimeUnit.DAYS);

                log.info("离线通知存储成功, userId={}", userId);
            }

        } catch (Exception e) {
            log.error("处理离线通知失败: {}", messageBody, e);
            throw e;
        }
    }
}
