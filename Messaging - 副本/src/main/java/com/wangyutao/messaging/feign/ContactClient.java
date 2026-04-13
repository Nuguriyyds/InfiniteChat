package com.wangyutao.messaging.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

@FeignClient(name = "ContactService", fallbackFactory = ContactClientFallbackFactory.class)
public interface ContactClient {

    @PostMapping("/api/contact/checkFriends")
    Map<String, Object> checkFriends(@RequestHeader("X-User-Id") Long userId,
                                     @RequestBody List<Long> contactIds);
}
