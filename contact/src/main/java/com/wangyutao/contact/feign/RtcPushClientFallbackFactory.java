package com.wangyutao.contact.feign;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class RtcPushClientFallbackFactory implements FallbackFactory<RtcPushClient> {

    @Override
    public RtcPushClient create(Throwable cause) {
        return new RtcPushClient() {
            @Override
            public String pushFriendApplication(Long userId, Map<String, Object> notification) {
                log.warn("好友申请推送降级, userId={}, cause={}", userId, cause.getMessage());
                return null;
            }

            @Override
            public String pushNewSession(Long userId, Map<String, Object> notification) {
                log.warn("新会话通知推送降级, userId={}, cause={}", userId, cause.getMessage());
                return null;
            }
        };
    }
}
