package com.wangyutao.authenticationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * JWT 登出失效点 - 与 Gateway 共用 Redis key {@code jwt:logout:{userId}}。
 * Value 为最后一次登出的毫秒时间戳；网关比较 {@code token.iat < logoutAt} 拒绝「登出前签发」的 Token。
 * <p>
 * 登录/续签<strong>不</strong>删除该 key：新 AT 的 {@code iat} 晚于 logoutAt 即可通过；旧被盗 AT 仍因 iat 较早而被拒。
 * TTL 与 AccessToken 最大有效期对齐。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtBlacklistService {

    private final StringRedisTemplate redisTemplate;

    /** 必须与 {@code com.wangyutao.gateway.service.JwtBlacklistService} 中前缀一致 */
    private static final String LOGOUT_PREFIX = "jwt:logout:";

    private static final long ACCESS_TOKEN_TTL_HOURS = 2;

    /**
     * 登出时写入最后登出时间戳（毫秒）
     */
    public void addToBlacklist(String userId) {
        String key = LOGOUT_PREFIX + userId;
        long logoutAt = System.currentTimeMillis();
        redisTemplate.opsForValue().set(key, String.valueOf(logoutAt), ACCESS_TOKEN_TTL_HOURS, TimeUnit.HOURS);
        log.info("用户登出时间已记录: userId={}, logoutAt={}, TTL={}h", userId, logoutAt, ACCESS_TOKEN_TTL_HOURS);
    }
}
