package com.wangyutao.messaging.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wangyutao.messaging.mapper.UserSessionMapper;
import com.wangyutao.messaging.model.entity.UserSession;
import com.wangyutao.messaging.service.UserSessionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserSessionServiceImpl extends ServiceImpl<UserSessionMapper, UserSession> implements UserSessionService {

    @Override
    public List<Long> getUserIdsBySessionId(String sessionId) { // 🌟 必须改为 Long

        // 1. 构造查询条件：查这个群里，且状态正常的成员
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getSessionId, sessionId)
                .eq(UserSession::getStatus, 1);

        // 2. 查出实体列表
        List<UserSession> userSessions = this.list(wrapper);

        // 3. 🌟 利用 Java 8 Stream API，优雅地提取出 userId 列表
        return userSessions.stream()
                .map(UserSession::getUserId)
                .collect(Collectors.toList());
    }
}