package com.wangyutao.moment.service;

public interface MomentNotifyService {

    void pushLikeNotification(Long momentOwnerId, Long likerUserId, Long momentId);

    void pushCommentNotification(Long momentOwnerId, Long commenterUserId, Long momentId, String commentContent);
}
