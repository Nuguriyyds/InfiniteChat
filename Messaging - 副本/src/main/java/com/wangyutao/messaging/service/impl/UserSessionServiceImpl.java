package com.wangyutao.messaging.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wangyutao.messaging.mapper.UserSessionMapper;
import com.wangyutao.messaging.model.entity.UserSession;
import com.wangyutao.messaging.service.UserSessionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserSessionServiceImpl extends ServiceImpl<UserSessionMapper, UserSession> implements UserSessionService {

    @Override
    public List<Long> getUserIdsBySessionId(String sessionId) {
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getSessionId, sessionId)
                .eq(UserSession::getStatus, 1);
        List<UserSession> userSessions = this.list(wrapper);
        return userSessions.stream()
                .map(UserSession::getUserId)
                .collect(Collectors.toList());
    }

    @Override
    public void updateLastAckSeq(Long userId, String sessionId, Long seq) {
        LambdaUpdateWrapper<UserSession> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(UserSession::getUserId, userId)
                .eq(UserSession::getSessionId, sessionId)
                .lt(UserSession::getLastAckSeq, seq)
                .set(UserSession::getLastAckSeq, seq);
        this.update(wrapper);
    }

    @Override
    public Map<String, Long> getLastAckSeqMap(Long userId) {
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getUserId, userId)
                .eq(UserSession::getStatus, 1)
                .select(UserSession::getSessionId, UserSession::getLastAckSeq);
        List<UserSession> sessions = this.list(wrapper);
        return sessions.stream()
                .collect(Collectors.toMap(
                        UserSession::getSessionId,
                        s -> s.getLastAckSeq() != null ? s.getLastAckSeq() : 0L
                ));
    }
}