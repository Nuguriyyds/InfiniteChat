package com.wangyutao.offlineservice.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wangyutao.offlineservice.mapper.OfflineMessageMapper;
import com.wangyutao.offlineservice.model.entity.OfflineMessage;
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
public class OfflineMessageCleanTask {

    private final OfflineMessageMapper offlineMessageMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String LOCK_KEY = "im:task:offline-message-clean";
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanExpiredMessages() {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL);
        if (acquired == null || !acquired) {
            log.info("其他实例正在执行清理任务，跳过");
            return;
        }

        try {
            log.info("开始清理过期离线消息");

            LocalDateTime expireTime = LocalDateTime.now().minusDays(7);
            QueryWrapper<OfflineMessage> wrapper = new QueryWrapper<>();
            wrapper.lt("expire_at", expireTime);
            wrapper.last("LIMIT 1000");

            int deletedCount = 0;
            while (true) {
                List<OfflineMessage> expiredMessages = offlineMessageMapper.selectList(wrapper);
                if (expiredMessages.isEmpty()) {
                    break;
                }

                for (OfflineMessage message : expiredMessages) {
                    offlineMessageMapper.deleteById(message.getId());
                    deletedCount++;
                }

                log.info("已清理 {} 条过期消息", deletedCount);
                Thread.sleep(1000);
            }

            LocalDateTime readExpireTime = LocalDateTime.now().minusDays(3);
            QueryWrapper<OfflineMessage> readWrapper = new QueryWrapper<>();
            readWrapper.eq("status", 1);
            readWrapper.lt("created_at", readExpireTime);
            readWrapper.last("LIMIT 1000");

            int readDeletedCount = 0;
            while (true) {
                List<OfflineMessage> readMessages = offlineMessageMapper.selectList(readWrapper);
                if (readMessages.isEmpty()) {
                    break;
                }

                for (OfflineMessage message : readMessages) {
                    offlineMessageMapper.deleteById(message.getId());
                    readDeletedCount++;
                }

                log.info("已清理 {} 条已读消息", readDeletedCount);
                Thread.sleep(1000);
            }

            log.info("清理任务完成, 共清理 {} 条过期消息, {} 条已读消息",
                deletedCount, readDeletedCount);

        } catch (Exception e) {
            log.error("清理离线消息失败", e);
        } finally {
            redisTemplate.delete(LOCK_KEY);
        }
    }
}
