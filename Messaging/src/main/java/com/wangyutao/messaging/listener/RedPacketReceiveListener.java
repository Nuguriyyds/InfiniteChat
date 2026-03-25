package com.wangyutao.messaging.listener;

import com.alibaba.fastjson.JSON;
import com.wangyutao.messaging.constants.RedPacketConstants;
import com.wangyutao.messaging.model.dto.RedPacketReceiveMqMessage;
import com.wangyutao.messaging.service.RedPacketReceiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 抢红包异步落库消费者。
 * 从 Redis Pending Hash 读取金额，调用 Service 层事务方法完成 DB 落库，
 * 事务提交成功后再清理 Redis Pending Hash（保证清理在事务边界外）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "IM_RED_PACKET_RECEIVE",
        consumerGroup = "im-red-packet-receive-group"
)
public class RedPacketReceiveListener implements RocketMQListener<String> {

    private final RedPacketReceiveService redPacketReceiveService;
    private final StringRedisTemplate redisTemplate;

    private static final String PENDING_KEY_PREFIX = RedPacketConstants.PENDING_AMOUNT_KEY_PREFIX.getValue();

    @Override
    public void onMessage(String messageBody) {
        RedPacketReceiveMqMessage msg = JSON.parseObject(messageBody, RedPacketReceiveMqMessage.class);
        if (msg == null) {
            log.warn("红包落库消息解析失败，body={}", messageBody);
            return;
        }

        Long redPacketId = msg.getRedPacketId();
        Long userId      = msg.getUserId();
        String pendingKey = PENDING_KEY_PREFIX + redPacketId;

        // 从 Redis Pending Hash 读取 Lua 脚本弹出的金额
        Object amountObj = redisTemplate.opsForHash().get(pendingKey, userId.toString());
        if (amountObj == null) {
            log.warn("Pending Hash 中无该用户金额，可能已处理过。redPacketId={}, userId={}", redPacketId, userId);
            return;
        }
        BigDecimal amount = new BigDecimal(amountObj.toString());

        // @Transactional 边界内：幂等校验 + 行锁扣减 + 写领取记录 + 加余额 + 写流水
        redPacketReceiveService.doSaveReceiveRecord(redPacketId, userId, amount);

        // @Transactional 边界外：DB 事务已提交，安全清理 Pending Hash
        redisTemplate.opsForHash().delete(pendingKey, userId.toString());
        log.info("红包落库完成并清理 Pending Hash，redPacketId={}, userId={}, amount={}", redPacketId, userId, amount);
    }
}
