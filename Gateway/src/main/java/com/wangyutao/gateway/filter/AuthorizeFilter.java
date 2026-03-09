package com.wangyutao.gateway.filter;

import com.wangyutao.gateway.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class AuthorizeFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // 1. 📋 合并白名单逻辑：登录、注册、续期、Swagger 全部放行
        if (path.matches("/api/v1/user/noToken/.*") ||
                path.equals("/api/v1/user/refreshToken") ||
                path.startsWith("/swagger") ||
                path.startsWith("/v3/api-docs") ||
                path.startsWith("/webjars")) {
            return chain.filter(exchange);
        }

        // 2. 🔍 智能取票：先从 Header 找 (针对普通 HTTP)
        String token = request.getHeaders().getFirst("Authorization");

        // 如果 Header 里没有，再试图从 URL 参数里找 (针对 Netty 的 WebSocket 握手)
        if (StringUtils.isBlank(token)) {
            token = request.getQueryParams().getFirst("token");
        }

        // 3. 拦截无票人员
        if (StringUtils.isBlank(token)) {
            log.warn("❌ 网关拦截：未携带 Token，路径: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        try {
            // 4. 🌟 无状态纯 CPU 验签（不再查 Redis！）
            Claims claims = JwtUtil.parse(token);
            if (claims == null) {
                throw new RuntimeException("Token 签名无效或已篡改");
            }

            // 拿到身份证号
            String userId = claims.getSubject();

            // 5. 🌟 降维魔法：把 userId 悄悄塞进请求头里，透传给背后的微服务！
            ServerHttpRequest mutatedRequest = request.mutate()
                    .header("X-User-Id", userId)
                    .build();
            ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

            log.debug("✅ 网关放行：鉴权成功，路径: {}, userId: {}", path, userId);
            return chain.filter(mutatedExchange);

        } catch (Exception e) {
            log.warn("❌ 网关拦截：Token 已过期或不合法，路径: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -1; // 最高优先级，必须在路由转发前验票
    }
}