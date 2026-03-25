package com.wangyutao.messaging.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wangyutao.messaging.model.entity.UserSession;

import java.util.List;
import java.util.Map;


public interface UserSessionService extends IService<UserSession> {

    List<Long> getUserIdsBySessionId(String sessionId);

    /**
     * 更新用户在指定会话中的 lastAckSeq，仅当新 seq 大于当前值时才更新（防止乱序回退）
     */
    void updateLastAckSeq(Long userId, String sessionId, Long seq);

    /**
     * 查询用户所有会话的 lastAckSeq，登录/重连时下发给客户端
     * @return Map<sessionId, lastAckSeq>
     */
    Map<String, Long> getLastAckSeqMap(Long userId);

}
