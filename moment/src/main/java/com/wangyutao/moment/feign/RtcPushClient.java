package com.wangyutao.moment.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "RealTimeCommunicationService", fallbackFactory = RtcPushClientFallbackFactory.class)
public interface RtcPushClient {

    @PostMapping("/api/v1/chat/push/moment")
    String pushMomentNotification(@RequestBody Map<String, Object> notification);
}
