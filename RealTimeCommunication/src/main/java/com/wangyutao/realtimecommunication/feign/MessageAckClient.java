package com.wangyutao.realtimecommunication.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "MessagingService")
public interface MessageAckClient {

    @PostMapping("/api/message/ack")
    void ackBySeq(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("seq") Long seq
    );

    @PostMapping("/api/message/ack")
    void ackByMessageId(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("messageId") String messageId
    );
}
