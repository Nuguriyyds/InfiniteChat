package com.wangyutao.messaging.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wangyutao.messaging.mapper.SessionMapper;
import com.wangyutao.messaging.mapper.UserSessionMapper;
import com.wangyutao.messaging.model.entity.Session;
import com.wangyutao.messaging.model.entity.UserSession;
import com.wangyutao.messaging.service.SessionService;
import com.wangyutao.messaging.service.UserSessionService;
import org.springframework.stereotype.Service;

@Service
public class SessionServiceImpl extends ServiceImpl<SessionMapper, Session> implements SessionService {

}
