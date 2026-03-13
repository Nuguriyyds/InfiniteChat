package com.wangyutao.realtimecommunication.redis;

import com.wangyutao.realtimecommunication.websocket.NettySessionManager;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserKickoutMessageListener implements MessageListener {

    private final NettySessionManager sessionManager;

    // 🌟 规范前缀，抽成常量更优雅
    private static final String ROUTE_PREFIX = "im:route:";

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            // 1. 拿到原始消息体，例如："im:route:888" 或 "888"
            String messageBody = new String(message.getBody(), StandardCharsets.UTF_8).trim();
            log.info("📢 收到 Redis 踢人广播，原始报文: {}", messageBody);

            // 2. 🌟 调用你写的优质辅助方法，剥离出干净的 userId 字符串
            String userIdStr = extractUserId(messageBody);

            // 3. 强转为 Long（如果传过来的不是数字，这里会被 catch 住，不会让程序崩溃）
            Long targetUserId = Long.valueOf(userIdStr);

            // 4. 去本地花名册里查，这个倒霉蛋连在咱们这台机器上吗？
            Channel channel = sessionManager.getChannel(targetUserId);

            if (channel != null) {
                // 5. 抓到人了！直接断开 TCP 连接
                log.warn("💥 正在执行跨服务物理踢出，切断用户 [{}] 的连接！", targetUserId);
                channel.close();
                // 💡 架构师注：channel.close() 之后，Netty 会自动触发 channelInactive 事件，
                // 从而自动调用 sessionManager.remove()，本地内存和 Redis 全局路由都会被清理得干干净净！
            } else {
                log.debug("🎯 目标用户 [{}] 不在当前 Netty 节点，忽略该广播。", targetUserId);
            }

        } catch (NumberFormatException e) {
            log.error("❌ 踢人广播解析异常：userId 格式非法，无法转换为 Long！", e);
        } catch (Exception e) {
            log.error("❌ 处理踢人广播时发生未知异常", e);
        }
    }

    /**
     * 辅助方法：智能提取 userId
     * 兼容直接发 "888" 或发 "im:route:888" 的情况
     */
    private String extractUserId(String messageBody) {
        if (messageBody.startsWith(ROUTE_PREFIX)) {
            // 砍掉前缀，留下纯数字
            return messageBody.substring(ROUTE_PREFIX.length());
        }
        return messageBody; // 如果本来就没前缀，直接返回
    }
}