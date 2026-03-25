package com.wangyutao.gateway.filter;

import com.wangyutao.gateway.service.JwtBlacklistService;
import com.wangyutao.gateway.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizeFilter implements GlobalFilter, Ordered {

    private final JwtBlacklistService jwtBlacklistService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        if (path.matches("/api/v1/user/noToken/.*") ||
                path.equals("/api/v1/user/refreshToken") ||
                path.startsWith("/swagger") ||
                path.startsWith("/v3/api-docs") ||
                path.startsWith("/webjars") ||
                path.startsWith("/api/message")) { // 压测临时白名单，压测完删除
            return chain.filter(exchange);
        }

        String token = request.getHeaders().getFirst("Authorization");

        if (StringUtils.isBlank(token)) {
            token = request.getQueryParams().getFirst("token");
        }

        if (StringUtils.isNotBlank(token) && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        if (StringUtils.isBlank(token)) {
            log.warn("网关拦截：未携带 Token，路径: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        Claims claims;
        try {
            claims = JwtUtil.parse(token);
            if (claims == null) {
                return rejectUnauthorized(exchange, path, "Token 签名无效或已篡改");
            }
        } catch (Exception e) {
            return rejectUnauthorized(exchange, path, e.getMessage());
        }

        String userId = claims.getSubject();
        long tokenIssuedAt = claims.getIssuedAt().getTime();

        // 响应式登出校验：比较 Token 签发时间与用户登出时间
        return jwtBlacklistService.getLogoutTime(userId)
                .flatMap(logoutTime -> {
                    if (logoutTime > 0 && tokenIssuedAt < logoutTime) {
                        log.warn("网关拦截：Token 签发于登出之前，userId: {}, iat: {}, logoutAt: {}, path: {}",
                                userId, tokenIssuedAt, logoutTime, path);
                        return rejectUnauthorized(exchange, path, "用户已登出，Token 已失效");
                    }

                    ServerHttpRequest mutatedRequest = request.mutate()
                            .header("X-User-Id", userId)
                            .build();
                    ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

                    log.debug("网关放行：鉴权成功，路径: {}, userId: {}", path, userId);
                    return chain.filter(mutatedExchange);
                });
    }

    private Mono<Void> rejectUnauthorized(ServerWebExchange exchange, String path, String reason) {
        log.warn("网关拦截：Token 已过期或不合法，路径: {}, 原因: {}", path, reason);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");

        String resultJson = "{\"code\":401,\"message\":\"Token已过期或不合法，请重新登录\",\"data\":null}";
        org.springframework.core.io.buffer.DataBuffer buffer =
                response.bufferFactory().wrap(resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
