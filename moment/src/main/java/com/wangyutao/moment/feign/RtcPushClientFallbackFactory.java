package com.wangyutao.moment.feign;

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
            public String pushMomentNotification(Map<String, Object> notification) {
                log.warn("朋友圈通知推送降级, cause={}", cause.getMessage());
                return null;
            }
        };
    }
}
