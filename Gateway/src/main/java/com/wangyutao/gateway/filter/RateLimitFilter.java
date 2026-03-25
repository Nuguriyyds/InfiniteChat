package com.wangyutao.gateway.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 分布式限流过滤器 - 终极原生 Reactive 版 (抛弃 Redisson)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate reactiveRedisTemplate;

    private static final String RATE_LIMIT = "100000"; // 压测阈值
    private static final String RATE_INTERVAL = "60";  // 60秒 (1分钟)

    // 固定窗口 Lua 脚本：incr -> 首次设过期 -> 超阈值返回0拦截 -> 否则返回1放行
    private static final String LUA_SCRIPT =
            "local current = redis.call('incr', KEYS[1]) " +
            "if tonumber(current) == 1 then " +
            "    redis.call('expire', KEYS[1], tonumber(ARGV[1])) " +
            "end " +
            "if tonumber(current) > tonumber(ARGV[2]) then " +
            "    return 0 " +
            "end " +
            "return 1 ";

    private static final RedisScript<Long> REDIS_SCRIPT = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isWhitelist(path)) {
            return chain.filter(exchange);
        }

        String clientIp = getClientIp(exchange);
        String rateLimitKey = "rate_limit:ip:" + clientIp;

        List<String> keys = Collections.singletonList(rateLimitKey);
        List<String> args = Arrays.asList(RATE_INTERVAL, RATE_LIMIT);

        return reactiveRedisTemplate.execute(REDIS_SCRIPT, keys, args)
                .next()
                .flatMap(result -> {
                    if (result == 0L) {
                        log.warn("限流触发: ip={}, path={}", clientIp, path);
                        return buildErrorResponse(exchange);
                    }
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> buildErrorResponse(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        String resultJson = "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}";
        org.springframework.core.io.buffer.DataBuffer buffer =
                response.bufferFactory().wrap(resultJson.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private boolean isWhitelist(String path) {
        return path.startsWith("/swagger") ||
               path.startsWith("/v3/api-docs") ||
               path.startsWith("/webjars") ||
               path.startsWith("/api/message"); // 压测临时白名单，压测完删除
    }

    private String getClientIp(ServerWebExchange exchange) {
        String ip = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
