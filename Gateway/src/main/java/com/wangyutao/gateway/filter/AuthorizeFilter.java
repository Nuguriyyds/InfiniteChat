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

        // 🌟 补全缺失的代码：如果 Header 里没有，尝试从 URL 参数中获取 (针对 WebSocket)
        if (StringUtils.isBlank(token)) {
            token = request.getQueryParams().getFirst("token");
        }

        // 如果 Header 里没有，再试图从 URL 参数里找 (针对 Netty 的 WebSocket 握手)
        // 🌟 标准协议修复：剥离 Bearer 前缀
        if (StringUtils.isNotBlank(token) && token.startsWith("Bearer ")) {
            token = token.substring(7); // 截掉 "Bearer "
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

            // 5. 🔥 黑名单检查：使用布隆过滤器判断用户是否已登出
            if (jwtBlacklistService.isInBlacklist(userId)) {
                log.warn("❌ 网关拦截：用户已登出（黑名单），userId: {}, path: {}", userId, path);
                throw new RuntimeException("用户已登出，Token 已失效");
            }

            // 6. 🌟 降维魔法：把 userId 悄悄塞进请求头里，透传给背后的微服务！
            ServerHttpRequest mutatedRequest = request.mutate()
                    .header("X-User-Id", userId)
                    .build();
            ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

            log.debug("✅ 网关放行：鉴权成功，路径: {}, userId: {}", path, userId);
            return chain.filter(mutatedExchange);

        } catch (Exception e) {
            log.warn("❌ 网关拦截：Token 已过期或不合法，路径: {}, 原因: {}", path, e.getMessage());
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");

            // 🌟 组装符合你系统的全局统一 JSON 格式
            String resultJson = "{\"code\":401,\"message\":\"Token已过期或不合法，请重新登录\",\"data\":null}";

            org.springframework.core.io.buffer.DataBuffer buffer = response.bufferFactory().wrap(resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return response.writeWith(reactor.core.publisher.Mono.just(buffer));
        }
    }

    @Override
    public int getOrder() {
        return -1; // 最高优先级，必须在路由转发前验票
    }
}