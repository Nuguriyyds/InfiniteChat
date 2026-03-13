package com.wangyutao.realtimecommunication.config;

import com.wangyutao.realtimecommunication.redis.UserKickoutMessageListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisSubscriberConfig {

    @Bean
    public RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory,
                                                   UserKickoutMessageListener kickoutListener) { // ❌ 删除了 offlineListener 注入
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 🌟 1. 监听 Auth 服务的“主动踢人”广播 (目标：断开 JVM 里的 TCP 连接)
        String kickoutTopic = "userLogout";
        container.addMessageListener(kickoutListener, new PatternTopic(kickoutTopic));

        // ❌ 删除了 offlineTopic 的监听！
        // 💡 架构师注：现在 Netty 节点在断开连接时，会直接通过 NettySessionManager
        // 精准擦除 Redis 里的 im:route:{userId}，再也不需要全网大喇叭广播了，性能拉满！

        return container;
    }
}