package com.wangyutao.messaging.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * MQ 发送专用线程池，将 asyncSendOrderly 从 Tomcat 请求线程中剥离。
 * asyncSendOrderly 在 RocketMQ producer 内部队列满时会阻塞调用线程；
 * 用独立线程池承接，Tomcat 线程可以立即返回，不被 MQ 尾延迟拖垮。
 */
@Configuration
public class MqSendExecutorConfig {

    @Bean(name = "mqSendExecutor")
    public Executor mqSendExecutor() {
        return new ThreadPoolExecutor(
                16,                              // 核心线程数
                64,                              // 最大线程数
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8192),  // 队列容量，防止 OOM
                r -> new Thread(r, "mq-send-" + r.hashCode()),
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时降级：由调用方线程兜底执行
        );
    }
}
