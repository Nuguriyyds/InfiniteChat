    package com.wangyutao.realtimecommunication.service;

    import cn.hutool.core.bean.BeanUtil;
    import cn.hutool.json.JSONUtil;
    import com.wangyutao.realtimecommunication.enums.MessageTypeEnum;
    import com.wangyutao.realtimecommunication.enums.PushTypeEnum;
    import com.wangyutao.realtimecommunication.model.dto.GatewayPushPacket;
    import com.wangyutao.realtimecommunication.model.entity.*;
    import com.wangyutao.realtimecommunication.model.push.*;
    import lombok.RequiredArgsConstructor;
    import lombok.extern.slf4j.Slf4j;
    import org.apache.rocketmq.spring.core.RocketMQTemplate;
    import org.springframework.data.redis.core.StringRedisTemplate;
    import org.springframework.messaging.support.MessageBuilder;
    import org.springframework.stereotype.Service;

    import java.util.List;

    @Slf4j
    @Service
    @RequiredArgsConstructor
    public class NettyMessageService {

        private final StringRedisTemplate redisTemplate;
        private final RocketMQTemplate rocketMQTemplate;

        private static final String NOTIFY_TOPIC = "IM_NOTIFY";

        /**
         * 通知类推送：走独立的 IM_NOTIFY topic，与聊天消息隔离
         */
        public void sendPush(Integer pushTypeCode, Object data, Long receiveUserId) {
            if (receiveUserId == null || data == null) {
                log.error("推送参数不完整: type={}, userId={}", pushTypeCode, receiveUserId);
                return;
            }

            String targetNodeId = redisTemplate.opsForValue().get("im:route:" + receiveUserId);
            String mqTag = (targetNodeId == null || targetNodeId.isEmpty()) ? "OFFLINE" : targetNodeId;

            MessageDTO messageDTO = new MessageDTO().setType(pushTypeCode).setData(data);
            String finalJsonToFrontend = JSONUtil.toJsonStr(messageDTO);

            GatewayPushPacket packet = new GatewayPushPacket(
                    java.util.Collections.singletonList(receiveUserId),
                    finalJsonToFrontend
            );

            org.springframework.messaging.Message<String> mqMessage = MessageBuilder
                    .withPayload(JSONUtil.toJsonStr(packet)).build();

            rocketMQTemplate.asyncSend(NOTIFY_TOPIC + ":" + mqTag, mqMessage, new org.apache.rocketmq.client.producer.SendCallback() {
                @Override
                public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                    log.info("通知推送投递成功, topic={}, tag={}, 用户={}", NOTIFY_TOPIC, mqTag, receiveUserId);
                }
                @Override
                public void onException(Throwable e) {
                    log.error("通知推送投递失败, 用户: {}", receiveUserId, e);
                }
            });
        }

        // =====================================================================
        // 下面的业务方法完全复用你的原版代码，一行都不用改！
        // 因为它们底层调用的 sendPush 已经完成了“单机到分布式”的基因突变。
        // =====================================================================
        /*
        public void sendMessageToUser(Message message) {
            // ... (你原来的代码逻辑，遍历 receiveUserIds 调用 sendPush) ...
        } */

        public void sendNewSessionNotification(NewSessionNotification notification, Long userId) {
            sendPush(PushTypeEnum.NEW_SESSION_NOTIFICATION.getCode(), notification, userId);
        }

        public void sendFriendApplicationNotification(FriendApplicationNotification notification, Long userId) {
            sendPush(PushTypeEnum.FRIEND_APPLICATION_NOTIFICATION.getCode(), notification, userId);
        }

        public void sendNewGroupSessionNotification(NewGroupSessionNotification notification, Long userId) {
            sendPush(PushTypeEnum.NEW_SESSION_NOTIFICATION.getCode(), notification, userId);
        }

        public void sendNoticeMoment(MomentNotification notification) {
            List<Long> userIds = notification.getReceiveUserIds();
            if (userIds == null || userIds.isEmpty()) return;
            notification.setReceiveUserIds(null); // 节省带宽
            for (Long userId : userIds) {
                sendPush(PushTypeEnum.MOMENT_NOTIFICATION.getCode(), notification, userId);
            }
        }
    }