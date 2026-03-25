package com.wangyutao.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * JWT 登出服务 - 基于 Redis String + TTL (Gateway 响应式版本)
 * 与 AuthenticationService 共享同一份 Redis 数据，
 * 使用 ReactiveStringRedisTemplate 适配 WebFlux 非阻塞模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtBlacklistService {

    private final ReactiveStringRedisTemplate reactiveRedisTemplate;

    private static final String LOGOUT_PREFIX = "jwt:logout:";

    /**
     * 响应式获取用户登出时间戳，不存在则返回 0
     */
    public Mono<Long> getLogoutTime(String userId) {
        return reactiveRedisTemplate.opsForValue()
                .get(LOGOUT_PREFIX + userId)
                .map(Long::parseLong)
                .defaultIfEmpty(0L);
    }
}
