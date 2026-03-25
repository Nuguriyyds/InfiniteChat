package com.wangyutao.contact.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "RealTimeCommunicationService", fallbackFactory = RtcPushClientFallbackFactory.class)
public interface RtcPushClient {

    @PostMapping("/api/v1/chat/push/friendApplication/{userId}")
    String pushFriendApplication(@PathVariable("userId") Long userId, @RequestBody Map<String, Object> notification);

    @PostMapping("/api/v1/chat/push/newSession/{userId}")
    String pushNewSession(@PathVariable("userId") Long userId, @RequestBody Map<String, Object> notification);
}
