package com.wangyutao.messaging.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wangyutao.messaging.constants.RedPacketConstants;
import com.wangyutao.messaging.exception.ServiceException;
import com.wangyutao.messaging.listener.RedPacketTxListener;
import com.wangyutao.messaging.mapper.BalanceLogMapper;
import com.wangyutao.messaging.mapper.RedPacketMapper;
import com.wangyutao.messaging.mapper.RedPacketReceiveMapper;
import com.wangyutao.messaging.mapper.UserBalanceMapper;
import com.wangyutao.messaging.model.dto.ReceiveRedPacketRequest;
import com.wangyutao.messaging.model.dto.ReceiveRedPacketResponse;
import com.wangyutao.messaging.model.dto.RedPacketReceiveMqMessage;
import com.wangyutao.messaging.model.entity.BalanceLog;
import com.wangyutao.messaging.model.entity.RedPacketReceive;
import com.wangyutao.messaging.model.enums.BalanceLogType;
import com.wangyutao.messaging.model.enums.RedPacketStatus;
import com.wangyutao.messaging.service.RedPacketReceiveService;
import com.wangyutao.messaging.utils.IdGenerator;
import cn.hutool.core.date.DateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedPacketReceiveServiceImpl extends ServiceImpl<RedPacketReceiveMapper, RedPacketReceive>
        implements RedPacketReceiveService {

    @Qualifier("redPacketTxRocketMQTemplate")
    private final RocketMQTemplate redPacketTxRocketMQTemplate;
    private final StringRedisTemplate redisTemplate;
    private final RedPacketMapper redPacketMapper;
    private final RedPacketReceiveMapper redPacketReceiveMapper;
    private final UserBalanceMapper userBalanceMapper;
    private final BalanceLogMapper balanceLogMapper;
    private final IdGenerator idGenerator;

    private static final String DESTINATION      = "IM_RED_PACKET_RECEIVE";
    private static final String COUNT_KEY_PREFIX  = RedPacketConstants.RED_PACKET_KEY_PREFIX.getValue();
    private static final Integer CLAIMED          = RedPacketStatus.CLAIMED.getStatus();

    @Override
    public ReceiveRedPacketResponse receiveRedPacket(ReceiveRedPacketRequest request) {
        Long userId      = request.getUserId();
        Long redPacketId = request.getRedPacketId();

        // 阶段一：极速拦截，count <= 0 直接打回
        String countKey = COUNT_KEY_PREFIX + redPacketId;
        String countVal = redisTemplate.opsForValue().get(countKey);
        if (countVal != null && Long.parseLong(countVal) <= 0) {
            return new ReceiveRedPacketResponse(null, CLAIMED);
        }

        String corrId = UUID.randomUUID().toString();
        CompletableFuture<BigDecimal> future = new CompletableFuture<>();
        RedPacketTxListener.FUTURE_CACHE.put(corrId, future);

        try {
            RedPacketReceiveMqMessage payload = new RedPacketReceiveMqMessage(redPacketId, userId, null);
            org.springframework.messaging.Message<String> mqMsg = MessageBuilder
                    .withPayload(JSON.toJSONString(payload))
                    .setHeader("RED_PACKET_ID", redPacketId.toString())
                    .setHeader("USER_ID", userId.toString())
                    .build();

            // 传入 Object[] 供 executeLocalTransaction 解析
            redPacketTxRocketMQTemplate.sendMessageInTransaction(
                    DESTINATION, mqMsg, new Object[]{redPacketId, userId, corrId});

            // 阻塞等待 Lua 执行结果，最多 3 秒
            BigDecimal amount = future.get(3, TimeUnit.SECONDS);

            if (BigDecimal.ZERO.compareTo(amount) == 0) {
                return new ReceiveRedPacketResponse(null, CLAIMED);       // 已抢完
            }
            if (new BigDecimal("-1").compareTo(amount) == 0) {
                return new ReceiveRedPacketResponse(null, 0);             // 已领取过
            }
            return new ReceiveRedPacketResponse(amount, 0);               // 抢到了

        } catch (TimeoutException e) {
            log.error("抢红包等待超时，redPacketId={}, userId={}", redPacketId, userId);
            throw new ServiceException("系统繁忙，请重试");
        } catch (Exception e) {
            log.error("抢红包异常，redPacketId={}, userId={}", redPacketId, userId, e);
            throw new ServiceException("系统繁忙，请重试");
        } finally {
            // 防内存泄漏，无论成功失败都清理 Future
            RedPacketTxListener.FUTURE_CACHE.remove(corrId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void doSaveReceiveRecord(Long redPacketId, Long userId, BigDecimal amount) {
        // 绝对幂等：先查 DB 是否已有记录
        int exists = redPacketReceiveMapper.countByRedPacketAndUser(redPacketId, userId);
        if (exists > 0) {
            log.info("重复落库，跳过。redPacketId={}, userId={}", redPacketId, userId);
            return;
        }

        // 行锁扣减红包库存
        int rows = redPacketMapper.deductRedPacketRowLock(redPacketId, amount);
        if (rows == 0) {
            throw new ServiceException("红包库存不足，无法落库");
        }

        // 插入领取记录
        RedPacketReceive receive = new RedPacketReceive();
        receive.setRedPacketReceiveId(idGenerator.nextId());
        receive.setRedPacketId(redPacketId);
        receive.setReceiverId(userId);
        receive.setAmount(amount);
        receive.setReceivedAt(LocalDateTime.now());
        try {
            redPacketReceiveMapper.insert(receive);
        } catch (DuplicateKeyException e) {
            log.info("唯一索引兜底，重复插入，跳过。redPacketId={}, userId={}", redPacketId, userId);
            return;
        }

        // 加用户余额
        userBalanceMapper.addBalanceAtomically(userId, amount);

        // 写资金流水
        BalanceLog balanceLog = new BalanceLog();
        balanceLog.setBalanceLogId(idGenerator.nextId());
        balanceLog.setUserId(userId);
        balanceLog.setAmount(amount);
        balanceLog.setType(BalanceLogType.RECEIVE_RED_PACKET.getType());
        balanceLog.setRelatedId(redPacketId);
        balanceLog.setCreatedAt(DateTime.now());
        balanceLogMapper.insert(balanceLog);
    }
}
