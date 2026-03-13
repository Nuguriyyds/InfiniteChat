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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import cn.hutool.json.JSONUtil;

import java.util.concurrent.TimeUnit;


@Component
@Slf4j
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class MessageInboundHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final NettySessionManager sessionManager;
    private final StringRedisTemplate redisTemplate;
    // 🌟 1. 定义一个专门用来记录超时次数的贴纸
    public static final AttributeKey<Integer> IDLE_COUNTER_KEY = AttributeKey.valueOf("IDLE_COUNTER");


    // 🌟 注入当前节点的动态 ID（在 application.yml 配置或启动参数传入，本地默认 NODE_1）
    @Value("${im.node.id:NODE_1}")
    private String localNodeId;
    /**
     * 1. 核心接收消息引擎：从这里分发所有的聊天、ACK 和心跳包
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame textWebSocketFrame) throws Exception {
        Long senderId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();

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
                ctx.close();
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
        sessionManager.remove(ctx.channel());
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
            Long userId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();

            // 获取当前超时次数，默认为 0
            Integer currentCount = ctx.channel().attr(IDLE_COUNTER_KEY).get();
            if (currentCount == null) {
                currentCount = 0;
            }

            currentCount++; // 次数 +1

            if (currentCount >= 999) {
                log.error("用户 [{}] 连续 {} 次心跳超时，强制断开连接", userId, currentCount);
                ctx.close(); // 超过 3 次，拔管子
            } else {
                // 还没到 3 次，更新贴纸，给客户端留机会
                ctx.channel().attr(IDLE_COUNTER_KEY).set(currentCount);
                log.warn("用户 [{}] 触发读空闲，当前连续超时次数: {}", userId, currentCount);

                // 🌟 【核心改造：双重心跳】服务端不摆烂了，主动发一个心跳包去问客户端“还在吗？”
                MessageDTO pingMsg = new MessageDTO();
                pingMsg.setType(ClientMessageTypeEnum.HEART_BEAT.getCode()); // 这里复用你们的 HEART_BEAT 类型
                pingMsg.setData("SERVER_PING"); // 附带一个标记，告诉前端这是服务端发来的探活

                ctx.channel().writeAndFlush(new TextWebSocketFrame(JSONUtil.toJsonStr(pingMsg)));

            }

        } else if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            Long userId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();
            // 🌟 1. 获取他在全网的旧路由
            String oldNodeId = redisTemplate.opsForValue().get("im:route:" + userId);

            // 🌟 2. 分布式双杀逻辑
            if (oldNodeId != null && !oldNodeId.equals(localNodeId)) {
                // 💥 场景 A：跨节点异地登录！旧连接在别的机器上。
                // 拿起大喇叭，通过咱们写好的 Redis Pub/Sub 触发跨节点踢人！
                log.info("🌐 跨节点异地登录，发送全网踢人广播, userId: {}, 旧节点: {}", userId, oldNodeId);
                redisTemplate.convertAndSend("userLogout", String.valueOf(userId));
            } else {
                // 💥 场景 B：同节点异地登录！旧连接就在本机的 ConcurrentHashMap 里。
                Channel oldChannel = sessionManager.getChannel(userId);
                if (oldChannel != null && oldChannel != ctx.channel()) {
                    log.info("🏠 同节点异地登录，踢除本机旧连接, userId: {}", userId);
                    oldChannel.close(); // 本机直接拔管子
                }
            }

            // 存入本地内存大管家
            sessionManager.add(userId, ctx.channel());
            ctx.channel().attr(IDLE_COUNTER_KEY).set(0);

            // 🌟 3. 注册全网路由！用新节点覆盖旧节点！
            // (注意：覆盖动作一定要在发广播之后，配合之前的“防误杀”逻辑完美闭环)
            redisTemplate.opsForValue().set("im:route:" + userId, localNodeId, 30, TimeUnit.MINUTES);
            log.info("✅ 全网路由登记/覆盖成功: im:route:{} -> {} (TTL: 30分钟)", userId, localNodeId);
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * 4. 统一的下线清理逻辑
     */
//    private void offline(ChannelHandlerContext ctx) {
//
//        Long userId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();
//
//        if (userId != null) {
//            // 1. 从花名册移除
//            sessionManager.remove(ctx.channel());
//
//            // 🌟 闭环关键 2：连接断开，撕毁全网路由，防止 Tomcat 继续往这个 Node 发送死信
//            log.info("🌐 全网路由清理成功: im:route:{}", userId);
//
//            // 2. 【精华吸收 3】分布式广播通知：告诉全网这个 userId 下线了
//        }
//        ctx.close();
//    }

    private void processACK(MessageDTO msg){
        // 处理客户端成功返回的数据
        AckData ackData = JSONUtil.toBean(msg.getData().toString(), AckData.class);
        log.info("ackData:{}",ackData);
        log.info("推送消息成功！");
    }

    private void processHeartBeat(ChannelHandlerContext ctx, MessageDTO msg){
        Long userId = ctx.channel().attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();
        if (userId != null) {
            // 🌟 核心：每次心跳都刷新 Redis 路由的过期时间，防止进程崩溃后产生死路由
            redisTemplate.expire("im:route:" + userId, 30, TimeUnit.MINUTES);
        }
        log.debug("收到来自客户端的心跳包 PING，已自动为其连接续期。");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Netty 通道发生异常: ", cause);
        ctx.close();
    }
}
