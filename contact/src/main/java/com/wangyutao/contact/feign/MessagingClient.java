package com.wangyutao.contact.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name = "MessagingService", fallbackFactory = MessagingClientFallbackFactory.class)
public interface MessagingClient {

    @PostMapping("/api/message/session/createSingle")
    Map<String, Object> createSingleSession(@RequestParam("userIdA") Long userIdA, @RequestParam("userIdB") Long userIdB);
}
