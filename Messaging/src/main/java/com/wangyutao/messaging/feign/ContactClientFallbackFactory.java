package com.wangyutao.messaging.feign;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ContactClientFallbackFactory implements FallbackFactory<ContactClient> {

    @Override
    public ContactClient create(Throwable cause) {
        return new ContactClient() {
            @Override
            public Map<String, Object> checkFriends(Long userId, List<Long> contactIds) {
                log.warn("校验好友关系降级(跳过校验), userId={}, contactIds={}, cause={}",
                        userId, contactIds, cause.getMessage());
                return null;
            }
        };
    }
}
