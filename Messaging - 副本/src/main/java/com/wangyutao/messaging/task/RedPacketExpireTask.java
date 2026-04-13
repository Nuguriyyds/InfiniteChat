package com.wangyutao.messaging.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wangyutao.messaging.model.entity.RedPacket;
import com.wangyutao.messaging.constants.RedPacketConstants;
import com.wangyutao.messaging.model.enums.RedPacketStatus;
import com.wangyutao.messaging.service.RedPacketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedPacketExpireTask {

    private final RedPacketService redPacketService;
    private final StringRedisTemplate redisTemplate;

    private static final String LOCK_KEY = "im:task:red-packet-expire";
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private static final int BATCH_SIZE = 200;

    @Scheduled(cron = "0 0 */1 * * ?")
    public void scanExpiredRedPackets() {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL);
        if (acquired == null || !acquired) {
            log.info("其他实例正在执行红包过期扫描，跳过");
            return;
        }

        try {
            int expireHours = RedPacketConstants.RED_PACKET_EXPIRE_HOURS.getIntValue();
            LocalDateTime expireTime = LocalDateTime.now().minusHours(expireHours);

            LambdaQueryWrapper<RedPacket> query = new LambdaQueryWrapper<RedPacket>()
                    .eq(RedPacket::getStatus, RedPacketStatus.UNCLAIMED.getStatus())
                    .lt(RedPacket::getCreatedAt, expireTime)
                    .last("LIMIT " + BATCH_SIZE);

            List<RedPacket> expiredPackets = redPacketService.list(query);

            if (expiredPackets.isEmpty()) {
                log.info("兜底扫描：无过期红包");
                return;
            }

            int successCount = 0;
            for (RedPacket packet : expiredPackets) {
                try {
                    redPacketService.handleExpiredRedPacket(packet.getRedPacketId());
                    successCount++;
                } catch (Exception e) {
                    log.error("兜底扫描：红包过期处理失败, redPacketId={}", packet.getRedPacketId(), e);
                }
            }
            log.info("兜底扫描完成: 共{}个过期红包, 成功处理{}个", expiredPackets.size(), successCount);
        } catch (Exception e) {
            log.error("兜底扫描红包过期任务异常", e);
        } finally {
            redisTemplate.delete(LOCK_KEY);
        }
    }
}
