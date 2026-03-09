package com.wangyutao.realtimecommunication.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.wangyutao.realtimecommunication.enums.MessageTypeEnum;
import com.wangyutao.realtimecommunication.enums.PushTypeEnum;
import com.wangyutao.realtimecommunication.model.entity.*;
import com.wangyutao.realtimecommunication.model.push.FriendApplicationNotification;
import com.wangyutao.realtimecommunication.model.push.MomentNotification;
import com.wangyutao.realtimecommunication.model.push.NewGroupSessionNotification;
import com.wangyutao.realtimecommunication.model.push.NewSessionNotification;
import com.wangyutao.realtimecommunication.websocket.NettySessionManager;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NettyMessageService {

    private final NettySessionManager sessionManager;

    /**
     * 核心推送引擎：将任意数据包装成 WebSocket 帧推给指定用户
     */
    public void sendPush(Integer pushTypeCode, Object data, String receiveUserId) {
        if (receiveUserId == null || data == null) {
            log.error("推送参数不完整: type={}, userId={}", pushTypeCode, receiveUserId);
            return;
        }

        // 1. O(1) 极速从花名册中找到目标用户的水管
        Channel channel = sessionManager.getChannel(receiveUserId);

        // 2. 如果水管存在且畅通，直接写数据
        if (channel != null && channel.isActive()) {
            MessageDTO messageDTO = new MessageDTO()
                    .setType(pushTypeCode)
                    .setData(data);

            String jsonMsg = JSONUtil.toJsonStr(messageDTO);
            TextWebSocketFrame frame = new TextWebSocketFrame(jsonMsg);

            channel.writeAndFlush(frame).addListener(future -> {
                if (future.isSuccess()) {
                    log.info("🚀 成功推送实时消息给用户 [{}]: {}", receiveUserId, jsonMsg);
                } else {
                    log.error("❌ 推送消息给用户 [{}] 失败", receiveUserId, future.cause());
                }
            });
        } else {
            // 3. 优雅降级：用户离线，绝不抛异常！
            log.debug("⚠️ 用户 [{}] 当前不在线(或不在本机)，跳过实时推送。", receiveUserId);
        }
    }

    // =====================================================================
    // 👇 下面是补充的业务分发逻辑，完美对接你的 Controller 和各种业务场景
    // =====================================================================

    /**
     * 1. 核心聊天消息分发 (处理来自 RcvMsgController 的请求)
     * 将数据库层的 Message 实体转换为具体的推送 DTO
     */
    public void sendMessageToUser(Message message) {
        if (message == null || message.getType() == null) return;

        MessageTypeEnum typeEnum = MessageTypeEnum.fromCode(message.getType());
        if (typeEnum == null) {
            log.warn("未知的聊天消息类型: {}", message.getType());
            return;
        }

        switch (typeEnum) {
            case TEXT_MESSAGE:
                TextMessage textMessage = new TextMessage();
                BeanUtils.copyProperties(message, textMessage);
                TextMessageBody textBean = BeanUtil.toBean(message.getBody(), TextMessageBody.class);
                textMessage.setBody(textBean);

                // 遍历接收者，复用底层 sendPush
                List<Long> textReceiveUserIds = textMessage.getReceiveUserIds();
                textMessage.setReceiveUserIds(null); // 节省带宽，不发给前端
                if (textReceiveUserIds != null) {
                    for (Long receiverId : textReceiveUserIds) {
                        sendPush(PushTypeEnum.MESSAGE_NOTIFICATION.getCode(), textMessage, String.valueOf(receiverId));
                    }
                }
                break;

            case PICTURE_MESSAGE:
                PictureMessage pictureMessage = new PictureMessage();
                BeanUtils.copyProperties(message, pictureMessage);
                PictureMessageBody pictureBean = BeanUtil.toBean(message.getBody(), PictureMessageBody.class);
                pictureMessage.setBody(pictureBean);

                if (pictureMessage.getReceiveUserIds() != null) {
                    for (Long receiverId : pictureMessage.getReceiveUserIds()) {
                        sendPush(PushTypeEnum.MESSAGE_NOTIFICATION.getCode(), pictureMessage, String.valueOf(receiverId));
                    }
                }
                break;

            // 如果有红包消息等，可以继续在此处堆叠 case RED_PACKET_MESSAGE:
            default:
                log.warn("暂不支持该类型消息的实时推送: {}", typeEnum);
                break;
        }
    }

    /**
     * 2. 发送新单聊会话通知
     */
    public void sendNewSessionNotification(NewSessionNotification notification, String userId) {
        sendPush(PushTypeEnum.NEW_SESSION_NOTIFICATION.getCode(), notification, userId);
    }

    /**
     * 3. 发送好友申请通知
     */
    public void sendFriendApplicationNotification(FriendApplicationNotification notification, String userId) {
        sendPush(PushTypeEnum.FRIEND_APPLICATION_NOTIFICATION.getCode(), notification, userId);
    }

    /**
     * 4. 发送新群聊会话通知
     */
    public void sendNewGroupSessionNotification(NewGroupSessionNotification notification, String userId) {
        sendPush(PushTypeEnum.NEW_SESSION_NOTIFICATION.getCode(), notification, userId);
    }

    /**
     * 5. 发送朋友圈更新通知 (群发广播)
     */
    public void sendNoticeMoment(MomentNotification notification) {
        List<Long> userIds = notification.getReceiveUserIds();
        if (userIds == null || userIds.isEmpty()) return;

        // 性能优化：将接收者列表置空，避免在循环广播时把庞大的 ID 列表发给每一个客户端
        notification.setReceiveUserIds(null);

        for (Long userId : userIds) {
            sendPush(PushTypeEnum.MOMENT_NOTIFICATION.getCode(), notification, String.valueOf(userId));
        }
    }
}