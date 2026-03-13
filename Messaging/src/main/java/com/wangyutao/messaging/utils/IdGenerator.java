package com.wangyutao.messaging.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
@Component
public class IdGenerator {

    // ==============================
    // 雪花算法的核心位移与掩码参数
    // ==============================
    private final long twepoch = 1672531200000L; // 自定义纪元时间 (例如 2023-01-01)，可延长系统寿命
    private final long workerIdBits = 5L;
    private final long datacenterIdBits = 5L;
    private final long maxWorkerId = ~(-1L << workerIdBits); // 31
    private final long maxDatacenterId = ~(-1L << datacenterIdBits); // 31
    private final long sequenceBits = 12L;
    private final long workerIdShift = sequenceBits;
    private final long datacenterIdShift = sequenceBits + workerIdBits;
    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;
    private final long sequenceMask = ~(-1L << sequenceBits); // 4095

    // ==============================
    // 运行时的状态参数
    // ==============================
    private long workerId;
    private long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    /**
     * 🌟 Spring 启动时自动初始化：根据机器信息动态计算 WorkId
     */
    @PostConstruct
    public void init() {
        try {
            InetAddress ip = InetAddress.getLocalHost();
            String hostAddress = ip.getHostAddress();
            String hostName = ip.getHostName();

            // 1. 根据 IP 地址的哈希值动态计算 workerId
            this.workerId = Math.abs(hostAddress.hashCode() % (maxWorkerId + 1));
            // 2. 根据主机名的哈希值动态计算 datacenterId
            this.datacenterId = Math.abs(hostName.hashCode() % (maxDatacenterId + 1));

            log.info("🚀 TianmuIdGenerator 初始化成功! [WorkerId: {}, DatacenterId: {}]", workerId, datacenterId);
        } catch (UnknownHostException e) {
            log.warn("获取机器 IP 失败，采用随机 fallback 策略");
            this.workerId = (long) (Math.random() * (maxWorkerId + 1));
            this.datacenterId = (long) (Math.random() * (maxDatacenterId + 1));
        }
    }

    /**
     * 🌟 核心方法：获取下一个全局唯一的分布式 ID (解决时钟回拨)
     */
    public synchronized Long nextId() {
        long timestamp = timeGen();

        // 🚨 核心亮点：时钟回拨防御机制
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {
                try {
                    // 1. 容忍 5ms 内的回拨，让当前线程休眠等待
                    log.warn("检测到轻微时钟回拨 {} ms，线程等待补齐...", offset);
                    Thread.sleep(offset << 1); // 睡双倍时间确保越过回拨点
                    timestamp = timeGen();
                    if (timestamp < lastTimestamp) {
                        throw new RuntimeException("时钟回拨恢复失败");
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                // 2. 严重回拨 (比如运维手动调了系统时间)
                // 面试亮点：此时可以选择抛出异常，或改变 workerId (this.workerId = (this.workerId + 1) % 31)
                log.error("致命级时钟回拨，时间倒流了 {} ms", offset);
                throw new RuntimeException(String.format("系统时钟异常回拨 %d 毫秒，拒绝生成 ID", offset));
            }
        }

        // 同一毫秒内，序列号自增
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & sequenceMask;
            // 如果这一毫秒内的 ID 已经用完了 (超过 4095 个)，则自旋等待下一毫秒
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            // 不同毫秒，序列号重置为 0
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 位移拼接生成最终 ID
        long id = ((timestamp - twepoch) << timestampLeftShift)
                | (datacenterId << datacenterIdShift)
                | (workerId << workerIdShift)
                | sequence;

        // 直接返回 String 彻底斩断前端 Long 精度丢失的隐患
        return id;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    private long timeGen() {
        return System.currentTimeMillis();
    }
}
