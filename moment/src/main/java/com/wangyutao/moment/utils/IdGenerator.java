package com.wangyutao.moment.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
@Component
public class IdGenerator {

    private final long twepoch = 1672531200000L;
    private final long workerIdBits = 5L;
    private final long datacenterIdBits = 5L;
    private final long maxWorkerId = ~(-1L << workerIdBits);
    private final long maxDatacenterId = ~(-1L << datacenterIdBits);
    private final long sequenceBits = 12L;
    private final long workerIdShift = sequenceBits;
    private final long datacenterIdShift = sequenceBits + workerIdBits;
    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;
    private final long sequenceMask = ~(-1L << sequenceBits);

    private long workerId;
    private long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    @PostConstruct
    public void init() {
        try {
            InetAddress ip = InetAddress.getLocalHost();
            String hostAddress = ip.getHostAddress();
            String hostName = ip.getHostName();

            this.workerId = Math.abs(hostAddress.hashCode() % (maxWorkerId + 1));
            this.datacenterId = Math.abs(hostName.hashCode() % (maxDatacenterId + 1));

            log.info("IdGenerator 初始化成功! [WorkerId: {}, DatacenterId: {}]", workerId, datacenterId);
        } catch (UnknownHostException e) {
            log.warn("获取机器 IP 失败，采用随机 fallback 策略");
            this.workerId = (long) (Math.random() * (maxWorkerId + 1));
            this.datacenterId = (long) (Math.random() * (maxDatacenterId + 1));
        }
    }

    public synchronized Long nextId() {
        long timestamp = timeGen();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {
                try {
                    log.warn("检测到轻微时钟回拨 {} ms，线程等待补齐...", offset);
                    Thread.sleep(offset << 1);
                    timestamp = timeGen();
                    if (timestamp < lastTimestamp) {
                        throw new RuntimeException("时钟回拨恢复失败");
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                log.error("致命级时钟回拨，时间倒流了 {} ms", offset);
                throw new RuntimeException(String.format("系统时钟异常回拨 %d 毫秒，拒绝生成 ID", offset));
            }
        }

        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & sequenceMask;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        long id = ((timestamp - twepoch) << timestampLeftShift)
                | (datacenterId << datacenterIdShift)
                | (workerId << workerIdShift)
                | sequence;

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
