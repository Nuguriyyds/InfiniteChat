package com.wangyutao.realtimecommunication.websocket;

import cn.hutool.json.JSONUtil;
import com.wangyutao.realtimecommunication.enums.ClientMessageTypeEnum;
import com.wangyutao.realtimecommunication.feign.MessageAckClient;
import com.wangyutao.realtimecommunication.model.entity.AckData;
import com.wangyutao.realtimecommunication.model.entity.MessageDTO;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class MessageInboundHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    public static final AttributeKey<Integer> IDLE_COUNTER_KEY = AttributeKey.valueOf("IDLE_COUNTER");

    private static final int MAX_IDLE_COUNT = 3;
    private static final long ROUTE_TTL_SECONDS = 20 * 60;

    private static final DefaultRedisScript<String> ROUTE_SWAP_SCRIPT;

    static {
        ROUTE_SWAP_SCRIPT = new DefaultRedisScript<>();
        ROUTE_SWAP_SCRIPT.setScriptText(
                "local oldNode = redis.call('GET', KEYS[1]) " +
                        "redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2])) " +
                        "return oldNode"
        );
        ROUTE_SWAP_SCRIPT.setResultType(String.class);
    }

    private final NettySessionManager sessionManager;
    private final StringRedisTemplate redisTemplate;
    private final MessageAckClient messageAckClient;

    @Value("${im.node.id:NODE_1}")
    private String localNodeId;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        Long userId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();
        if (userId == null) {
            log.warn("收到未鉴权通道消息，强制关闭连接");
            ctx.close();
            return;
        }

        ctx.channel().attr(IDLE_COUNTER_KEY).set(0);
        redisTemplate.expire("im:route:" + userId, ROUTE_TTL_SECONDS, TimeUnit.SECONDS);

        MessageDTO messageDTO = JSONUtil.toBean(frame.text(), MessageDTO.class);
        if (messageDTO == null || messageDTO.getType() == null) {
            log.warn("收到非法 WS 帧, userId={}, payload={}", userId, frame.text());
            return;
        }
        ClientMessageTypeEnum messageType = ClientMessageTypeEnum.of(messageDTO.getType());
        switch (messageType) {
            case HEART_BEAT:
                processHeartBeat(userId);
                break;
            case ACK:
                processACK(userId, messageDTO);
                break;
            case LOG_OUT:
                ctx.close();
                break;
            default:
                log.warn("不支持的客户端消息类型, userId={}, type={}", userId, messageDTO.getType());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        sessionManager.remove(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            Long userId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();
            Integer currentCount = ctx.channel().attr(IDLE_COUNTER_KEY).get();
            if (currentCount == null) {
                currentCount = 0;
            }
            currentCount++;

            if (currentCount >= MAX_IDLE_COUNT) {
                log.error("用户 [{}] 连续 {} 次心跳超时，强制断开连接", userId, currentCount);
                ctx.close();
            } else {
                ctx.channel().attr(IDLE_COUNTER_KEY).set(currentCount);
                MessageDTO pingMsg = new MessageDTO();
                pingMsg.setType(ClientMessageTypeEnum.HEART_BEAT.getCode());
                pingMsg.setData("SERVER_PING");
                ctx.channel().writeAndFlush(new TextWebSocketFrame(JSONUtil.toJsonStr(pingMsg)));
            }
        } else if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            Long userId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();
            String routeKey = "im:route:" + userId;
            String oldNodeId = redisTemplate.execute(
                    ROUTE_SWAP_SCRIPT,
                    Collections.singletonList(routeKey),
                    localNodeId,
                    String.valueOf(ROUTE_TTL_SECONDS)
            );

            Channel oldChannel = sessionManager.getChannel(userId);
            sessionManager.add(userId, ctx.channel());

            if (oldNodeId != null && !oldNodeId.equals(localNodeId) && !oldNodeId.equals("OFFLINE")) {
                log.info("跨节点异地登录，发送踢人广播, userId={}, oldNodeId={}", userId, oldNodeId);
                redisTemplate.convertAndSend("userLogout", oldNodeId + ":" + userId);
            } else if (oldChannel != null && oldChannel != ctx.channel()) {
                log.info("同节点异地登录，关闭本机旧连接, userId={}", userId);
                oldChannel.close();
            }
            ctx.channel().attr(IDLE_COUNTER_KEY).set(0);
            log.info("全网路由登记成功: {} -> {} (TTL: {}s)", routeKey, localNodeId, ROUTE_TTL_SECONDS);
        }
        super.userEventTriggered(ctx, evt);
    }

    private void processACK(Long userId, MessageDTO msg) {
        AckData ackData = JSONUtil.toBean(JSONUtil.toJsonStr(msg.getData()), AckData.class);
        if (ackData == null) {
            log.warn("收到空 ACK, userId={}", userId);
            return;
        }

        if (StringUtils.isNotBlank(ackData.getSessionId()) && ackData.getSeq() != null) {
            messageAckClient.ackBySeq(userId, ackData.getSessionId(), ackData.getSeq());
            log.debug("ACK 已回写 lastAckSeq, userId={}, sessionId={}, seq={}",
                    userId, ackData.getSessionId(), ackData.getSeq());
            return;
        }

        String messageId = StringUtils.defaultIfBlank(ackData.getMessageId(), ackData.getMsgUuid());
        if (StringUtils.isBlank(messageId)) {
            log.warn("ACK 缺少 sessionId+seq 或 messageId, userId={}, ackData={}", userId, ackData);
            return;
        }

        messageAckClient.ackByMessageId(userId, messageId);
        log.debug("ACK 已按 messageId 回写 lastAckSeq, userId={}, messageId={}", userId, messageId);
    }

    private void processHeartBeat(Long userId) {
        log.debug("收到客户端心跳, userId={}", userId);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Netty 通道异常", cause);
        ctx.close();
    }
}
