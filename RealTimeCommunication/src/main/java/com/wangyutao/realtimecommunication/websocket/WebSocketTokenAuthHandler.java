package com.wangyutao.realtimecommunication.websocket;

import cn.hutool.core.net.url.UrlQuery;
import com.wangyutao.realtimecommunication.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ChannelHandler.Sharable
@RequiredArgsConstructor
public class WebSocketTokenAuthHandler extends ChannelInboundHandlerAdapter {

    public static final AttributeKey<Long> USER_ID_KEY = AttributeKey.valueOf("USER_ID");

    private static final String LOGOUT_PREFIX = "jwt:logout:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;
            String uri = request.uri();

            UrlQuery query = UrlQuery.of(uri, null);
            String token = (String) query.get("token");
            if (token == null || token.trim().isEmpty()) {
                log.warn("拒绝 WebSocket 连接: 缺少 token 参数");
                sendErrorAndClose(ctx, request, HttpResponseStatus.UNAUTHORIZED);
                return;
            }

            try {
                Claims claims = JwtUtil.parse(token);
                if (claims == null) {
                    throw new IllegalArgumentException("token invalid");
                }

                String userIdStr = claims.getSubject();
                Long userId = Long.valueOf(userIdStr);
                if (userId == null) {
                    throw new IllegalArgumentException("token subject is null");
                }

                long tokenIssuedAt = claims.getIssuedAt() != null ? claims.getIssuedAt().getTime() : 0L;
                String logoutAtText = redisTemplate.opsForValue().get(LOGOUT_PREFIX + userIdStr);
                long logoutAt = logoutAtText != null ? Long.parseLong(logoutAtText) : 0L;
                if (logoutAt > 0 && tokenIssuedAt < logoutAt) {
                    log.warn("拒绝 WebSocket 连接: token 签发于登出之前, userId={}, iat={}, logoutAt={}",
                            userId, tokenIssuedAt, logoutAt);
                    sendErrorAndClose(ctx, request, HttpResponseStatus.UNAUTHORIZED);
                    return;
                }

                ctx.channel().attr(USER_ID_KEY).set(userId);
                log.info("WebSocket 鉴权成功, userId={}", userId);

                int queryIndex = uri.indexOf('?');
                request.setUri(queryIndex >= 0 ? uri.substring(0, queryIndex) : uri);
            } catch (Exception e) {
                log.warn("WebSocket 鉴权失败, uri={}", uri, e);
                sendErrorAndClose(ctx, request, HttpResponseStatus.UNAUTHORIZED);
                return;
            }
        }

        super.channelRead(ctx, msg);
    }

    private void sendErrorAndClose(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), status);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
