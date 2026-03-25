package com.wangyutao.messaging.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wangyutao.messaging.mapper.MsgFailoverMapper;
import com.wangyutao.messaging.model.entity.MsgFailover;
import com.wangyutao.realtimecommunication.model.dto.GatewayPushPacket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 本地消息表补偿任务
 * 定时扫描 im_msg_failover 表，将未处理的消息重新投递到 MQ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MsgFailoverRetryTask {

    private static final int MAX_RETRY_COUNT = 5;
    private static final long BATCH_DEADLINE_MS = 8_000L;

    private final MsgFailoverMapper msgFailoverMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelay = 10_000L)
    public void retrySend() {
        RLock lock = redissonClient.getLock("im:task:msg-failover-retry");

        if (!lock.tryLock()) {
            return;
        }

        try {
            QueryWrapper<MsgFailover> wrapper = new QueryWrapper<MsgFailover>()
                    .eq("status", 0)
                    .last("limit 500");
            List<MsgFailover> list = msgFailoverMapper.selectList(wrapper);
            if (list == null || list.isEmpty()) {
                return;
            }

            long deadline = System.currentTimeMillis() + BATCH_DEADLINE_MS;

            for (MsgFailover failover : list) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("兜底任务本批处理超时，剩余消息留到下一轮, 已处理至 id={}", failover.getId());
                    break;
                }

                try {
                    int retryCount = failover.getRetryCount() == null ? 0 : failover.getRetryCount();

                    if (retryCount >= MAX_RETRY_COUNT) {
                        log.error("兜底消息超过最大重试次数，转为死信, clientMsgId={}", failover.getClientMsgId());
                        failover.setStatus(-1);
                        failover.setUpdateTime(new Date());
                        msgFailoverMapper.updateById(failover);
                        continue;
                    }

                    String sessionId = failover.getSessionId();
                    String payloadJson = failover.getPayload();
                    if (payloadJson == null) {
                        continue;
                    }

                    GatewayPushPacket packet = com.alibaba.fastjson.JSON.parseObject(payloadJson, GatewayPushPacket.class);
                    if (packet == null || packet.getTargetUserIds() == null || packet.getTargetUserIds().isEmpty()) {
                        continue;
                    }

                    List<Long> targetUserIds = packet.getTargetUserIds();
                    List<String> routeKeys = new ArrayList<>();
                    for (Long userId : targetUserIds) {
                        routeKeys.add("im:route:" + userId);
                    }
                    List<String> nodes = redisTemplate.opsForValue().multiGet(routeKeys);
                    if (nodes == null) {
                        continue;
                    }

                    boolean allSuccess = true;

                    for (int i = 0; i < targetUserIds.size(); i++) {
                        Long userId = targetUserIds.get(i);
                        String nodeId = nodes.get(i);
                        if (nodeId == null || nodeId.isEmpty()) {
                            nodeId = "OFFLINE";
                        }

                        org.springframework.messaging.Message<String> mqMessage = MessageBuilder
                                .withPayload(com.alibaba.fastjson.JSON.toJSONString(
                                        new GatewayPushPacket(java.util.Collections.singletonList(userId), packet.getWsPayload())
                                ))
                                .setHeader("KEYS", failover.getClientMsgId())
                                .build();

                        String destination = "IM_CHAT:" + nodeId;

                        try {
                            rocketMQTemplate.syncSendOrderly(destination, mqMessage, sessionId, 3000L);
                        } catch (Exception e) {
                            log.error("兜底消息重投 MQ 失败, userId={}, clientMsgId={}", userId, failover.getClientMsgId(), e);
                            allSuccess = false;
                        }
                    }

                    if (allSuccess) {
                        failover.setStatus(1);
                        failover.setUpdateTime(new Date());
                        msgFailoverMapper.updateById(failover);
                        log.info("兜底消息重投成功, clientMsgId={}", failover.getClientMsgId());
                    } else {
                        failover.setRetryCount(retryCount + 1);
                        failover.setUpdateTime(new Date());
                        msgFailoverMapper.updateById(failover);
                    }

                } catch (Exception e) {
                    log.error("处理单条兜底消息失败, id={}", failover.getId(), e);
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
