package com.wangyutao.messaging.listener;

import com.wangyutao.messaging.service.RedPacketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "RED_PACKET_EXPIRE",
        consumerGroup = "red-packet-expire-group",
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class RedPacketExpireListener implements RocketMQListener<String> {

    private final RedPacketService redPacketService;

    @Override
    public void onMessage(String message) {
        try {
            Long redPacketId = Long.parseLong(message.trim());
            log.info("收到红包过期延迟消息, redPacketId={}", redPacketId);
            redPacketService.handleExpiredRedPacket(redPacketId);
            log.info("红包过期处理完成, redPacketId={}", redPacketId);
        } catch (NumberFormatException e) {
            log.error("红包过期消息格式错误, message={}", message, e);
        } catch (Exception e) {
            log.error("红包过期处理失败, message={}", message, e);
            throw e;
        }
    }
}
