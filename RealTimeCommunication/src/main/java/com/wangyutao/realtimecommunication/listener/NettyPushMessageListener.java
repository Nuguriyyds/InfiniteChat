package com.wangyutao.realtimecommunication.listener;

import com.alibaba.fastjson.JSON;
import com.wangyutao.realtimecommunication.model.dto.GatewayPushPacket;
import com.wangyutao.realtimecommunication.websocket.NettySessionManager;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "IM_CHAT", // 如果你未来拆了 IM_NOTICE，可以复制一个一模一样的 Listener
        consumerGroup = "netty-push-group-${im.node.id:NODE_1}",
        selectorExpression = "${im.node.id:NODE_1}",
        consumeMode = ConsumeMode.ORDERLY
)
public class NettyPushMessageListener implements RocketMQListener<String> {

    private final NettySessionManager nettySessionManager;

    @Override
    public void onMessage(String mqJsonPayload) {
        try {
            // 1. 🌟 只解析标准件纸箱！不关心里面装的是聊天还是通知
            GatewayPushPacket packet = JSON.parseObject(mqJsonPayload, GatewayPushPacket.class);

            List<Long> targetUserIds = packet.getTargetUserIds();
            String pushContent = packet.getWsPayload(); // 这就是准备发给前端的纯文本

            if (targetUserIds == null || targetUserIds.isEmpty() || pushContent == null) {
                return;
            }

            // 💡 架构师注：为了极致性能，提前包装好 Netty 的 WebSocket 帧，避免在 for 循环里重复 new 对象
            TextWebSocketFrame sharedFrame = new TextWebSocketFrame(pushContent);

            int pushSuccessCount = 0;

            // 2. 纯内存极速分发
            for (Long userId : targetUserIds) {
                Channel channel = nettySessionManager.getChannel(userId);

                if (channel != null && channel.isActive()) {
                    // 🌟 核心：复用同一个帧对象，每次发送前调用 retain() 增加引用计数
                    channel.writeAndFlush(sharedFrame.retain());
                    pushSuccessCount++;
                } else {
                    log.debug("用户 [{}] 不在当前节点，跳过推送", userId);
                }
            }

            // 🌟 最后释放原始帧的引用
            sharedFrame.release();

            log.info("MQ 消费下发完成, 目标人数: {}, 成功推送: {}", targetUserIds.size(), pushSuccessCount);

        } catch (Exception e) {
            log.error("Netty 消费 MQ 消息出现异常，消息内容: {}", mqJsonPayload, e);
            // 🌟 抛出异常，让 RocketMQ 自动重试 (最多 16 次)，失败后进入死信队列
            throw new RuntimeException("消息处理失败，触发重试", e);
        }
    }
}