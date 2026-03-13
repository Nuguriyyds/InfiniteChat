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

        // 🌟 抛弃单机的 SessionManager，拥抱分布式的 Redis 和 MQ！
        private final StringRedisTemplate redisTemplate;
        private final RocketMQTemplate rocketMQTemplate;

        /**
         * 🌟 终极推送引擎：将任意数据包装成标准件，利用 MQ 精准制导！
         */
        public void sendPush(Integer pushTypeCode, Object data, Long receiveUserId) {
            if (receiveUserId == null || data == null) {
                log.error("推送参数不完整: type={}, userId={}", pushTypeCode, receiveUserId);
                return;
            }

            // 1. 查全局字典：目标用户究竟连在哪台机器上？
            String targetNodeId = redisTemplate.opsForValue().get("im:route:" + receiveUserId);

            if (targetNodeId == null || targetNodeId.isEmpty()) {
                // 用户彻底离线，或者 App 处于后台被杀
                // 💡 架构师注：对于好友申请等重要通知，这里可以调用极光推送/苹果 APNs 发送离线手机弹窗
                log.debug("⚠️ 用户 [{}] 当前不在线，走离线拉取/APNs逻辑。", receiveUserId);
                return;
            }

            // 2. 包装成前端认识的格式
            MessageDTO messageDTO = new MessageDTO().setType(pushTypeCode).setData(data);
            String finalJsonToFrontend = JSONUtil.toJsonStr(messageDTO);

            // 🌟 3. 套上网关标准件纸箱！把收件人和包裹内容装进去
            GatewayPushPacket packet = new GatewayPushPacket(
                    java.util.Collections.singletonList(receiveUserId),
                    finalJsonToFrontend
            );

            // 4. 将标准件转为 JSON 扔进 MQ
            org.springframework.messaging.Message<String> mqMessage = MessageBuilder
                    .withPayload(JSONUtil.toJsonStr(packet)).build();

            // 💥 魔法在这里：无论这个 HTTP 请求打到了哪台机器，这条 MQ 最终都会精准掉落到 targetNodeId 的内存里！
            rocketMQTemplate.asyncSend("IM_CHAT:" + targetNodeId, mqMessage, new org.apache.rocketmq.client.producer.SendCallback() {
                @Override
                public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                    log.info("🚀 成功将实时通知扔进 MQ，目标机器: {}, 用户: {}", targetNodeId, receiveUserId);
                }
                @Override
                public void onException(Throwable e) {
                    log.error("❌ MQ 投递通知失败, 用户: {}", receiveUserId, e);
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