package com.wangyutao.authenticationservice.utils;


import com.wangyutao.authenticationservice.model.enums.ErrorEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLockExecutor {
    private final RedissonClient redissonClient;

    /**
     * 🌟 高级用法：函数式执行分布式锁逻辑
     * @param lockKey 锁的唯一标识
     * @param waitTime 等待锁的时间（毫秒）
     * @param leaseTime 持有锁的时间（毫秒，建议 -1 使用看门狗）
     * @param action 拿到锁后要执行的业务逻辑
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean isLock = false;
        try {
            // 尝试加锁
            isLock = lock.tryLock(waitTime, leaseTime, TimeUnit.MILLISECONDS);
            if (!isLock) {
                log.warn("❌ 获取分布式锁失败: {}", lockKey);
                throw new com.wangyutao.authenticationservice.exception.BusinessException(ErrorEnum.GET_LOCK_ERROR);
            }

            log.debug("✅ 获取分布式锁成功: {}", lockKey);
            return action.get(); // 执行业务逻辑

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new com.wangyutao.authenticationservice.exception.BusinessException(ErrorEnum.SYSTEM_ERROR);
        } finally {
            // 🌟 只有持有锁的线程才能释放锁，非常安全
            if (isLock && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("🔓 释放分布式锁: {}", lockKey);
            }
        }
    }
}
