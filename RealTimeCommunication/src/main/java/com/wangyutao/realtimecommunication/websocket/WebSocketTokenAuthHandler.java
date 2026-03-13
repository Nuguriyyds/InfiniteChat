package com.wangyutao.realtimecommunication.websocket;

import cn.hutool.core.net.url.UrlQuery;
import com.wangyutao.realtimecommunication.utils.JwtUtil; // 导入你搬过来的 JwtUtil
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

    // 注入 Redis，注意这里不要用 @Autowired，如果是单例组件可以通过构造器注入，或者在 NettyServer 组装时传入
    // private final StringRedisTemplate redisTemplate;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;
            String uri = request.uri();

            // 1. 从 URL 中提取 Token
            UrlQuery query = UrlQuery.of(uri, null);
            String token = (String) query.get("token");

            if (token == null || token.trim().isEmpty()) {
                log.warn("❌ 拒绝连接：缺少 token 参数");
                ctx.channel().writeAndFlush(new io.netty.handler.codec.http.DefaultFullHttpResponse(
                        request.protocolVersion(), HttpResponseStatus.UNAUTHORIZED));
                ctx.close();
                return;
            }

            try {
                // 🌟 2. 极致性能：纯 CPU 解析校验 JWT，不需要查 Redis！
                // 如果 Token 过期或被篡改，parse() 底层会抛异常，直接进入 catch 块
                String userIdStr = JwtUtil.parse(token).getSubject();
                Long userId = Long.valueOf(userIdStr);

                if (userId == null) {
                    throw new RuntimeException("Token 格式错误，无法解析 UserId");
                }

                // 3. 鉴权彻底通过！贴上便利贴
                ctx.channel().attr(USER_ID_KEY).set(userId);
                log.info("✅ 鉴权成功，允许升级 WebSocket, userId: {}", userId);

                // 4. 剥离 Token 参数，还原纯净的 WebSocket 路径给下一个 Handler
                request.setUri(uri.substring(0, uri.indexOf("?")));

            } catch (Exception e) {
                // 捕获到过期、篡改等异常，直接切断
                log.warn("❌ 鉴权失败：Token 不合法或已过期");
                sendErrorAndClose(ctx, request, HttpResponseStatus.UNAUTHORIZED);
                return;
            }
        }

        // 传递给下一个 Handler (比如 WebSocketServerProtocolHandler)
        super.channelRead(ctx, msg);
    }

    /**
     * 🌟 封装优雅的错误响应与断开逻辑
     */
    private void sendErrorAndClose(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), status);
        // 必须等 401 响应彻底写入网卡后，再安全关闭 TCP 连接，防止前端收到 Connection Reset
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}