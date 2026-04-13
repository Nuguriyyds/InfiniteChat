package com.wangyutao.authenticationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ThreadPoolConfig {

    @Bean("ioThreadPool")
    public Executor ioThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：处理常规发邮件任务
        executor.setCorePoolSize(10);
        // 最大线程数：遇到恶意狂刷验证码时，最多开到 50 个线程
        executor.setMaxPoolSize(50);
        // 队列容量：最多允许 200 个邮件任务排队
        executor.setQueueCapacity(200);
        // 线程名称前缀，方便打日志和排查线上 OOM
        executor.setThreadNamePrefix("IO-Mail-Thread-");
        // 🌟 拒绝策略：如果队列满了，由调用方（Tomcat 线程）自己去执行，保证任务绝对不丢！
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 初始化
        executor.initialize();
        return executor;
    }

    @Bean("logoutCompensationScheduler")
    public ScheduledExecutorService logoutCompensationScheduler() {
        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task);
            thread.setName("logout-compensation-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };

        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(4, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }
}
