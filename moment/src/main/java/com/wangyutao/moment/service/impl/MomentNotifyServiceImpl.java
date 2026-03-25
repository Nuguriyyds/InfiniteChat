package com.wangyutao.moment.service.impl;

import com.wangyutao.moment.constants.MomentConstants;
import com.wangyutao.moment.feign.RtcPushClient;
import com.wangyutao.moment.service.MomentNotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MomentNotifyServiceImpl implements MomentNotifyService {

    private final RtcPushClient rtcPushClient;

    @Override
    public void pushLikeNotification(Long momentOwnerId, Long likerUserId, Long momentId) {
        if (momentOwnerId == null || likerUserId == null) {
            return;
        }

        Map<String, Object> notification = new HashMap<>();
        notification.put("receiveUserIds", Collections.singletonList(momentOwnerId));
        notification.put("noticeType", MomentConstants.MOMENT_NOTIFICATION_TYPE_LIKE);
        notification.put("total", 1);

        pushToRtc(notification, momentOwnerId);
    }

    @Override
    public void pushCommentNotification(Long momentOwnerId, Long commenterUserId, Long momentId, String commentContent) {
        if (momentOwnerId == null || commenterUserId == null) {
            return;
        }

        Map<String, Object> notification = new HashMap<>();
        notification.put("receiveUserIds", Collections.singletonList(momentOwnerId));
        notification.put("noticeType", MomentConstants.MOMENT_NOTIFICATION_TYPE_COMMENT);
        notification.put("total", 1);

        pushToRtc(notification, momentOwnerId);
    }

    private void pushToRtc(Map<String, Object> notification, Long userId) {
        try {
            rtcPushClient.pushMomentNotification(notification);
            log.info("朋友圈通知推送成功, userId={}", userId);
        } catch (Exception e) {
            log.warn("朋友圈通知推送失败, userId={}", userId, e);
        }
    }
}
