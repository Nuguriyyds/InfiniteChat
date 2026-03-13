package com.wangyutao.messaging.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wangyutao.messaging.model.entity.UserSession;
import org.springframework.stereotype.Service;

import java.util.List;


public interface UserSessionService extends IService<UserSession> {

    List<Long> getUserIdsBySessionId(String sessionId);

}
