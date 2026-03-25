package com.wangyutao.messaging.listener;

import com.wangyutao.messaging.constants.RedPacketConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抢红包事务消息本地事务监听器。
 * executeLocalTransaction: 执行四合一 Lua 脚本，通过 CompletableFuture 唤醒挂起的 HTTP 线程。
 * checkLocalTransaction: Broker 回查时检查 Pending Hash 是否存在，精准定性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQTransactionListener(rocketMQTemplateBeanName = "redPacketTxRocketMQTemplate")
public class RedPacketTxListener implements RocketMQLocalTransactionListener {

    private final StringRedisTemplate redisTemplate;

    /** corrId -> CompletableFuture，供 Service 层挂起等待 Lua 结果 */
    public static final ConcurrentHashMap<String, CompletableFuture<BigDecimal>> FUTURE_CACHE =
            new ConcurrentHashMap<>();

    private static final String RECEIVED_SET_PREFIX = RedPacketConstants.RECEIVED_SET_KEY_PREFIX.getValue();
    private static final String COUNT_KEY_PREFIX    = RedPacketConstants.RED_PACKET_KEY_PREFIX.getValue();
    private static final String AMOUNTS_KEY_PREFIX  = "red_packet:amounts:";
    private static final String PENDING_KEY_PREFIX  = RedPacketConstants.PENDING_AMOUNT_KEY_PREFIX.getValue();

    private static final DefaultRedisScript<String> GRAB_SCRIPT;

    static {
        GRAB_SCRIPT = new DefaultRedisScript<>();
        GRAB_SCRIPT.setScriptText(RedPacketConstants.UNIFIED_GRAB_LUA.getValue());
        GRAB_SCRIPT.setResultType(String.class);
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        Object[] args      = (Object[]) arg;
        Long redPacketId   = (Long) args[0];
        Long userId        = (Long) args[1];
        String corrId      = (String) args[2];

        String receivedKey = RECEIVED_SET_PREFIX + redPacketId;
        String countKey    = COUNT_KEY_PREFIX + redPacketId;
        String amountKey   = AMOUNTS_KEY_PREFIX + redPacketId;
        String pendingKey  = PENDING_KEY_PREFIX + redPacketId;

        try {
            String result = redisTemplate.execute(
                    GRAB_SCRIPT,
                    Arrays.asList(receivedKey, countKey, amountKey, pendingKey),
                    userId.toString()
            );

            CompletableFuture<BigDecimal> future = FUTURE_CACHE.get(corrId);

            if (result == null || "0".equals(result)) {
                // 已抢完：BigDecimal.ZERO 作哨兵
                if (future != null) future.complete(BigDecimal.ZERO);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            if ("-1".equals(result)) {
                // 已领取过：BigDecimal(-1) 作哨兵
                if (future != null) future.complete(new BigDecimal("-1"));
                return RocketMQLocalTransactionState.ROLLBACK;
            }

            BigDecimal amount = new BigDecimal(result);
            if (future != null) future.complete(amount);
            return RocketMQLocalTransactionState.COMMIT;

        } catch (Exception e) {
            log.error("Lua 脚本执行异常，redPacketId={}, userId={}", redPacketId, userId, e);
            CompletableFuture<BigDecimal> future = FUTURE_CACHE.get(corrId);
            if (future != null) future.completeExceptionally(e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        try {
            String redPacketIdStr = (String) msg.getHeaders().get("RED_PACKET_ID");
            String userIdStr      = (String) msg.getHeaders().get("USER_ID");
            if (redPacketIdStr == null || userIdStr == null) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            String pendingKey = PENDING_KEY_PREFIX + redPacketIdStr;
            Object val = redisTemplate.opsForHash().get(pendingKey, userIdStr);
            if (val != null) {
                log.info("回查：Pending Hash 存在，补发 COMMIT。redPacketId={}, userId={}", redPacketIdStr, userIdStr);
                return RocketMQLocalTransactionState.COMMIT;
            }
            log.info("回查：Pending Hash 不存在，补发 ROLLBACK。redPacketId={}, userId={}", redPacketIdStr, userIdStr);
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            log.error("回查异常", e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}
