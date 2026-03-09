package com.wangyutao.realtimecommunication.config;


import com.wangyutao.realtimecommunication.redis.UserKickoutMessageListener;
import com.wangyutao.realtimecommunication.redis.UserOfflineListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisSubscriberConfig {

    @Bean
    public RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory,
                                                   UserKickoutMessageListener kickoutListener,
                                                   UserOfflineListener offlineListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 🌟 1. 监听 Auth 服务的“主动踢人”广播 (目标：断开 JVM 里的 TCP 连接)
        String kickoutTopic = "userLogout";
        container.addMessageListener(kickoutListener, new PatternTopic(kickoutTopic));

        // 🌟 2. 监听 Netty 自己的“掉线”广播 (目标：清理 Redis 里的全局路由)
        String offlineTopic = "im:user:offline";
        container.addMessageListener(offlineListener, new PatternTopic(offlineTopic));

        return container;
    }
}
