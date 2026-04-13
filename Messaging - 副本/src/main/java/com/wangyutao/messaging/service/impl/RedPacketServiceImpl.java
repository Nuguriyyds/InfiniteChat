package com.wangyutao.messaging.service.impl;

import cn.hutool.core.date.DateTime;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wangyutao.messaging.constants.RedPacketConstants;
import com.wangyutao.messaging.exception.ServiceException;
import com.wangyutao.messaging.mapper.BalanceLogMapper;
import com.wangyutao.messaging.mapper.RedPacketMapper;
import com.wangyutao.messaging.mapper.RedPacketReceiveMapper;
import com.wangyutao.messaging.mapper.UserBalanceMapper;
import com.wangyutao.messaging.model.dto.SendMsgRequest;
import com.wangyutao.messaging.model.dto.SendRedPacketRequest;
import com.wangyutao.messaging.model.entity.*;
import com.wangyutao.messaging.model.enums.BalanceLogType;
import com.wangyutao.messaging.model.enums.RedPacketStatus;
import com.wangyutao.messaging.model.vo.ResponseMsgVo;
import com.wangyutao.messaging.service.MessageService;
import com.wangyutao.messaging.service.RedPacketReceiveService;
import com.wangyutao.messaging.service.RedPacketService;
import com.wangyutao.messaging.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


@Slf4j
@RequiredArgsConstructor
@Service
public class RedPacketServiceImpl extends ServiceImpl<RedPacketMapper, RedPacket>
        implements RedPacketService {
    private final UserBalanceMapper userBalanceMapper;
    private final BalanceLogMapper balanceLogMapper;
    private final MessageService messageService; // 🌟 统一叫 MessageService
    private final StringRedisTemplate redisTemplate;
    private final IdGenerator idGenerator;
    private final RocketMQTemplate rocketMQTemplate;
    private final RedPacketMapper redPacketMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseMsgVo sendRedPacket(SendRedPacketRequest request) throws Exception {
        SendRedPacketRequest.Body body = request.getBody();
        validateSendRedPacketRequest(body);

        Long senderId = request.getSendUserId();
        BigDecimal totalAmount = body.getTotalAmount();
        int totalCount = body.getTotalCount();
        String sessionId = request.getSessionId();

        // 扣减余额
        deductUserBalanceSafe(senderId, totalAmount);

        // 创建红包记录
        RedPacket redPacket = createRedPacket(senderId, request, body, totalAmount, totalCount, body.getRedPacketType(), sessionId);

        // 记录余额变更日志
        createBalanceLog(senderId, totalAmount.negate(), BalanceLogType.SEND_RED_PACKET, redPacket.getRedPacketId());

        // 发送红包消息到 IM 通道
        ResponseMsgVo response = sendRedPacketMessage(request, redPacket);

        // 预热 Redis - 预先计算金额并存储
        preCalculateAndStoreAmounts(redPacket.getRedPacketId(), totalAmount, totalCount, body.getRedPacketType());

        // 发送 24 小时延迟消息，到期触发过期退款
        sendExpireDelayMessage(redPacket.getRedPacketId());

        return response;
    }
    
    /**
     * 🌟 P0-3: 预先计算红包金额并存储到 Redis
     * 
     * @param redPacketId 红包ID
     * @param totalAmount 总金额
     * @param totalCount 总个数
     * @param redPacketType 红包类型（1=普通，2=拼手气）
     */
    private void preCalculateAndStoreAmounts(Long redPacketId, BigDecimal totalAmount, 
                                            int totalCount, int redPacketType) {
        String countKey = RedPacketConstants.RED_PACKET_KEY_PREFIX.getValue() + redPacketId;
        String amountKey = "red_packet:amounts:" + redPacketId;
        
        // 生成金额列表
        List<BigDecimal> amounts;
        if (RedPacketConstants.RED_PACKET_TYPE_NORMAL.getIntValue().equals(redPacketType)) {
            // 普通红包：平均分配
            amounts = generateNormalAmounts(totalAmount, totalCount);
        } else {
            // 拼手气红包：二倍均值法
            amounts = generateRandomAmounts(totalAmount, totalCount);
        }
        
        // 存储到 Redis
        Duration expireTime = Duration.ofHours(RedPacketConstants.RED_PACKET_EXPIRE_HOURS.getIntValue());
        
        // 存储 count
        redisTemplate.opsForValue().set(countKey, String.valueOf(totalCount), expireTime);
        
        // 存储 amounts（使用 RPUSH 批量插入）
        for (BigDecimal amount : amounts) {
            redisTemplate.opsForList().rightPush(amountKey, amount.toString());
        }
        redisTemplate.expire(amountKey, expireTime);
        
        log.info("红包金额预计算完成，红包ID: {}, 类型: {}, 总金额: {}, 个数: {}", 
            redPacketId, redPacketType, totalAmount, totalCount);
    }
    
    /**
     * 生成普通红包金额列表（平均分配）
     */
    private List<BigDecimal> generateNormalAmounts(BigDecimal totalAmount, int totalCount) {
        List<BigDecimal> amounts = new ArrayList<>();
        BigDecimal avgAmount = totalAmount.divide(
            BigDecimal.valueOf(totalCount), 
            RedPacketConstants.DIVIDE_SCALE.getIntValue(), 
            RoundingMode.DOWN
        );
        
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < totalCount - 1; i++) {
            amounts.add(avgAmount);
            sum = sum.add(avgAmount);
        }
        
        // 最后一个红包拿剩余的（处理除不尽的情况）
        amounts.add(totalAmount.subtract(sum));
        
        return amounts;
    }
    
    /**
     * 生成拼手气红包金额列表（二倍均值法）
     * 保证公平性：每个人抢到大红包的概率相同
     */
    private List<BigDecimal> generateRandomAmounts(BigDecimal totalAmount, int totalCount) {
        List<BigDecimal> amounts = new ArrayList<>();
        BigDecimal remaining = totalAmount;
        BigDecimal minAmount = RedPacketConstants.MIN_AMOUNT.getBigDecimalValue();
        
        for (int i = 0; i < totalCount - 1; i++) {
            // 计算最大可分配金额：剩余平均值的 2 倍
            BigDecimal max = remaining
                .divide(BigDecimal.valueOf(totalCount - i), 2, RoundingMode.DOWN)
                .multiply(BigDecimal.valueOf(2));
            
            // 随机生成金额
            BigDecimal amount = generateRandomAmount(minAmount, max);
            amounts.add(amount);
            remaining = remaining.subtract(amount);
        }
        
        // 最后一个红包拿剩余的
        amounts.add(remaining);
        
        // 打乱顺序，保证公平性
        Collections.shuffle(amounts);
        
        return amounts;
    }
    
    /**
     * 生成指定范围内的随机金额
     */
    private BigDecimal generateRandomAmount(BigDecimal min, BigDecimal max) {
        if (max.compareTo(min) <= 0) {
            return min;
        }
        
        BigDecimal range = max.subtract(min);
        BigDecimal randomInRange = range.multiply(BigDecimal.valueOf(Math.random()));
        BigDecimal randomAmount = min.add(randomInRange)
            .setScale(RedPacketConstants.AMOUNT_SCALE.getDivideScale(), RoundingMode.DOWN);
        
        return randomAmount.compareTo(min) < 0 ? min : randomAmount;
    }

    private void sendExpireDelayMessage(Long redPacketId) {
        try {
            long delayMs = RedPacketConstants.RED_PACKET_EXPIRE_HOURS.getIntValue() * 3600 * 1000L;
            long deliverTimeMs = System.currentTimeMillis() + delayMs;

            org.springframework.messaging.Message<String> msg = MessageBuilder
                    .withPayload(String.valueOf(redPacketId))
                    .build();

            rocketMQTemplate.syncSendDeliverTimeMills("RED_PACKET_EXPIRE", msg, deliverTimeMs);
            log.info("红包过期延迟消息发送成功, redPacketId={}, 将在{}h后投递", redPacketId,
                    RedPacketConstants.RED_PACKET_EXPIRE_HOURS.getIntValue());
        } catch (Exception e) {
            log.warn("红包过期延迟消息发送失败（兜底定时任务会补偿）, redPacketId={}", redPacketId, e);
        }
    }

    /**
     * 将红包剩余个数设置到 Redis（已被 preCalculateAndStoreAmounts 替代）
     *
     * @param redPacketId 红包ID
     * @param totalCount  红包总个数
     * @deprecated 使用 preCalculateAndStoreAmounts 替代
     */
    @Deprecated
    private void setRedPacketCountToRedis(Long redPacketId, int totalCount) {
        String redisKey = RedPacketConstants.RED_PACKET_KEY_PREFIX.getValue() + redPacketId;
        redisTemplate.opsForValue().set(redisKey, String.valueOf(totalCount), Duration.ofHours(RedPacketConstants.RED_PACKET_EXPIRE_HOURS.getIntValue()));
    }

    /**
     * 发送红包消息
     *
     * @param request    发送红包请求
     * @param redPacket  红包对象
     * @return 响应消息
     * @throws ServiceException 发送消息失败
     */
    private ResponseMsgVo sendRedPacketMessage(SendRedPacketRequest request, RedPacket redPacket) throws ServiceException {
        SendMsgRequest sendMsgRequest = new SendMsgRequest();
        BeanUtils.copyProperties(request, sendMsgRequest);

        sendMsgRequest.setType(5);
        sendMsgRequest.setClientMsgId("rp_" + redPacket.getRedPacketId());
        RedPacketMessageBody redPacketMessageBody = new RedPacketMessageBody();
        redPacketMessageBody.setContent(String.valueOf(redPacket.getRedPacketId()));
        redPacketMessageBody.setRedPacketWrapperText(redPacket.getRedPacketWrapperText());
        sendMsgRequest.setBody(redPacketMessageBody);

        try {
            return messageService.sendMessage(sendMsgRequest);
        } catch (Exception e) {
            log.error("发送红包消息失败，红包ID: {}", redPacket.getRedPacketId(), e);
            throw new ServiceException("发送红包消息失败");
        }
    }


    /**
     * 验证发送红包请求的参数
     *
     * @param body 请求体
     * @throws ServiceException 参数验证失败
     */
    private void validateSendRedPacketRequest(SendRedPacketRequest.Body body) throws ServiceException {
        if (body == null)
            throw new ServiceException("请求体不能为空");

        BigDecimal totalAmount = body.getTotalAmount();
        int totalCount = body.getTotalCount();
        int redPacketType = body.getRedPacketType();

        if (totalAmount == null || totalAmount.compareTo(RedPacketConstants.MIN_AMOUNT.getBigDecimalValue()) < 0) {
            throw new ServiceException("红包总金额不能低于0.01元");
        }

        BigDecimal maxTotalAmount = RedPacketConstants.MAX_AMOUNT_PER_PACKET.getBigDecimalValue()
                .multiply(BigDecimal.valueOf(totalCount));
        if (totalAmount.compareTo(maxTotalAmount) > 0) {
            throw new ServiceException("红包总金额超过允许的最大值");
        }

        BigDecimal minAmountPerPacket = totalAmount.divide(
                BigDecimal.valueOf(totalCount),
                RedPacketConstants.DIVIDE_SCALE.getIntValue(),
                RoundingMode.DOWN
        );
        if (minAmountPerPacket.compareTo(RedPacketConstants.MIN_AMOUNT.getBigDecimalValue()) < 0) {
            throw new ServiceException("单个红包金额不能低于0.01元");
        }
        if (minAmountPerPacket.compareTo(RedPacketConstants.MAX_AMOUNT_PER_PACKET.getBigDecimalValue()) > 0) {
            throw new ServiceException("单个红包金额不能超过200元");
        }

        if (!RedPacketConstants.RED_PACKET_TYPE_NORMAL.getIntValue().equals(redPacketType) &&
                !RedPacketConstants.RED_PACKET_TYPE_RANDOM.getIntValue().equals(redPacketType)) {
            throw new ServiceException("无效的红包类型");
        }
    }

    private void deductUserBalanceSafe(Long userId, BigDecimal amount) throws ServiceException {
        // ✅ 正确做法：直接利用数据库行锁进行原子扣减
        // 对应 Mapper XML 的 SQL 应该是：
        // UPDATE user_balance SET balance = balance - #{amount} WHERE user_id = #{userId} AND balance >= #{amount}

        int updateCount = userBalanceMapper.deductBalanceAtomically(userId, amount);
        if (updateCount == 0) {
            throw new ServiceException("余额不足或账户异常");
        }
    }

    private RedPacket createRedPacket(Long senderId, SendRedPacketRequest request,
                                      SendRedPacketRequest.Body body,
                                      BigDecimal totalAmount,
                                      int totalCount,
                                      int redPacketType,
                                      String sessionId) {
        RedPacket redPacket = new RedPacket();
        redPacket.setRedPacketId(idGenerator.nextId());
        redPacket.setSenderId(senderId);
        redPacket.setSessionId(sessionId);

        // 若红包封面文案是否为空或为null则设置默认祝福语
        String text = body.getRedPacketWrapperText();
        if (text == null || text.trim().isEmpty()) {
            redPacket.setRedPacketWrapperText("恭喜发财，大吉大利");
        }else {
            redPacket.setRedPacketWrapperText(text);
        }
        redPacket.setRedPacketType(redPacketType);
        redPacket.setTotalAmount(totalAmount);
        redPacket.setTotalCount(totalCount);
        redPacket.setRemainingAmount(totalAmount);
        redPacket.setRemainingCount(totalCount);
        redPacket.setStatus(RedPacketStatus.UNCLAIMED.getStatus());
        redPacket.setCreatedAt(LocalDateTime.now());

        this.save(redPacket);
        return redPacket;
    }

    /**
     * 创建余额变更日志
     *
     * @param userId    用户ID
     * @param amount    变更金额
     * @param logType   变更类型
     * @param relatedId 关联ID（如红包ID）
     */
    private void createBalanceLog(Long userId, BigDecimal amount, BalanceLogType logType, Long relatedId) {
        BalanceLog balanceLog = new BalanceLog();
        balanceLog.setBalanceLogId(idGenerator.nextId());
        balanceLog.setUserId(userId);
        balanceLog.setAmount(amount);
        balanceLog.setType(logType.getType());
        balanceLog.setCreatedAt(DateTime.now());
        balanceLog.setRelatedId(relatedId);
        balanceLogMapper.insert(balanceLog);
    }




    /**
     * 处理红包过期
     *
     * @param redPacketId 红包ID
     */
    @Override
    @Transactional
    public void handleExpiredRedPacket(Long redPacketId) {
        int rows = redPacketMapper.casUpdateStatus(
                redPacketId,
                RedPacketStatus.UNCLAIMED.getStatus(),
                RedPacketStatus.EXPIRED.getStatus()
        );
        if (rows == 0) {
            log.info("红包已被处理（已领完/已过期/不存在），幂等跳过, redPacketId={}", redPacketId);
            return;
        }

        RedPacket redPacket = getById(redPacketId);
        if (redPacket == null) {
            log.error("CAS 更新成功但查询不到红包，数据异常, redPacketId={}", redPacketId);
            return;
        }

        BigDecimal remainingAmount = redPacket.getRemainingAmount();
        if (remainingAmount.compareTo(BigDecimal.ZERO) > 0) {
            refundRemainingAmount(redPacket);
        }
    }

    /**
     * 退还红包剩余金额并更新红包状态
     *
     * @param redPacket 红包对象
     * @throws ServiceException 退还余额失败
     */
    private void refundRemainingAmount(RedPacket redPacket) throws ServiceException {
        Long senderId = redPacket.getSenderId();
        BigDecimal remainingAmount = redPacket.getRemainingAmount();

        // 🌟 完美复用：一句话搞定，绝对安全的物理级加钱！
        int updateCount = userBalanceMapper.addBalanceAtomically(senderId, remainingAmount);
        if (updateCount != 1) {
            throw new ServiceException("退还余额失败");
        }

        // 记录余额变更日志 (注意这里的类型是 REFUND_RED_PACKET)
        createBalanceLog(senderId, remainingAmount, BalanceLogType.REFUND_RED_PACKET, redPacket.getRedPacketId());

        // 更新红包状态为已过期
        redPacket.setStatus(RedPacketStatus.EXPIRED.getStatus());
        this.updateById(redPacket);
    }
}
