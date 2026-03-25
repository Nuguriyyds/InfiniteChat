package com.wangyutao.contact.feign;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class MessagingClientFallbackFactory implements FallbackFactory<MessagingClient> {

    @Override
    public MessagingClient create(Throwable cause) {
        return new MessagingClient() {
            @Override
            public Map<String, Object> createSingleSession(Long userIdA, Long userIdB) {
                log.warn("创建单聊会话降级, userIdA={}, userIdB={}, cause={}", userIdA, userIdB, cause.getMessage());
                return null;
            }
        };
    }
}
