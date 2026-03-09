package com.wangyutao.realtimecommunication.websocket;

import com.wangyutao.realtimecommunication.enums.ClientMessageTypeEnum;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import cn.hutool.json.JSONUtil;


@Component
@Slf4j
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class MessageInboundHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final NettySessionManager sessionManager;
    private final StringRedisTemplate redisTemplate;
    // 🌟 1. 定义一个专门用来记录超时次数的贴纸
    public static final AttributeKey<Integer> IDLE_COUNTER_KEY = AttributeKey.valueOf("IDLE_COUNTER");

    /**
     * 1. 核心接收消息引擎：从这里分发所有的聊天、ACK 和心跳包
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame textWebSocketFrame) throws Exception {
        String senderId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();

        // 🌟 极限防御：虽然前面的 AuthHandler 正常情况下已经拦截了，
        // 但为了防止 Netty 内部走线异常导致未打标签的报文溜进来，加个判空直接踢掉。
        if (senderId == null) {
            log.warn("⚠️ 收到未鉴权通道的消息，强制关闭连接");
            ctx.close();
            return;
        }
        // 🌟 2. 精华：只要收到任何消息（包括心跳），立刻将超时计数器清零！
        ctx.channel().attr(IDLE_COUNTER_KEY).set(0);

        MessageDTO messageDTO = JSONUtil.toBean(textWebSocketFrame.text(), MessageDTO.class);
        log.debug("收到来自用户 [{}] 的消息: {}", senderId, messageDTO);

        ClientMessageTypeEnum messageType = ClientMessageTypeEnum.of(messageDTO.getType());
        switch (messageType) {
            case HEART_BEAT:
                // 返回心跳应答包
                processHeartBeat(ctx, messageDTO);
                break;
            case ACK:
                // 处理消息签收确认
                processACK(messageDTO);
                break;
            case LOG_OUT:
                // 主动退出登录
                offline(ctx);
                break;
            // TODO: 这里将来加上你的 CHAT_MESSAGE (真实聊天) 和 AI_MESSAGE (呼叫千言) 的路由！
            default:
                log.warn("不支持的消息类型: {}", messageDTO.getType());
        }
    }

    /**
     * 2. 生命周期：用户刚刚连接上，或者断开连接
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 连接自然断开（拔网线、关浏览器），执行下线清理
        offline(ctx);
        super.channelInactive(ctx);
    }

    /**
     * 3. 事件触发：吸取精华，处理空闲超时和握手成功
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            // 🌟 3. 触发空闲事件，处理 3 次重试逻辑
            IdleStateEvent event = (IdleStateEvent) evt;
            String userId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();

            // 获取当前超时次数，默认为 0
            Integer currentCount = ctx.channel().attr(IDLE_COUNTER_KEY).get();
            if (currentCount == null) {
                currentCount = 0;
            }

            currentCount++; // 次数 +1

            if (currentCount >= 3) {
                log.error("用户 [{}] 连续 {} 次心跳超时，强制断开连接", userId, currentCount);
                offline(ctx); // 超过 3 次，拔管子
            } else {
                // 还没到 3 次，更新贴纸，给客户端留机会
                ctx.channel().attr(IDLE_COUNTER_KEY).set(currentCount);
                log.warn("用户 [{}] 触发读空闲，当前连续超时次数: {}", userId, currentCount);
            }

        } else if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            String userId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();
            Channel oldChannel = sessionManager.getChannel(userId);

            if (oldChannel != null && oldChannel != ctx.channel()) {
                log.info("账号在异地登录，踢除旧连接, userId: {}", userId);
                oldChannel.close();
            }

            sessionManager.add(userId, ctx.channel());
            // 🌟 4. 握手成功时，初始化计数器
            ctx.channel().attr(IDLE_COUNTER_KEY).set(0);
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * 4. 统一的下线清理逻辑
     */
    private void offline(ChannelHandlerContext ctx) {

        String userId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();

        if (userId != null) {
            // 1. 从花名册移除
            sessionManager.remove(ctx.channel());

            // 2. 【精华吸收 3】分布式广播通知：告诉全网这个 userId 下线了
            redisTemplate.convertAndSend("im:user:offline", userId);
        }
        ctx.close();
    }

    private void processACK(MessageDTO msg){
        // 处理客户端成功返回的数据
        AckData ackData = JSONUtil.toBean(msg.getData().toString(), AckData.class);
        log.info("ackData:{}",ackData);
        log.info("推送消息成功！");
    }

    private void processHeartBeat(ChannelHandlerContext ctx, MessageDTO msg){
        log.info("收到心跳包");
        MessageDTO messageDTO = new MessageDTO();
        messageDTO.setType(ClientMessageTypeEnum.HEART_BEAT.getCode());
        TextWebSocketFrame frame = new TextWebSocketFrame(JSONUtil.toJsonStr(messageDTO));
        ctx.channel().writeAndFlush(frame);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Netty 通道发生异常: ", cause);
        offline(ctx);
    }
}
