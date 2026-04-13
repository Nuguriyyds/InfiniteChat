package com.wangyutao.messaging.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wangyutao.messaging.model.entity.User;
import com.wangyutao.messaging.service.UserService;
import com.wangyutao.messaging.mapper.UserMapper;
import org.springframework.stereotype.Service;

/**
* @author yutao
* @description 针对表【user(用户核心表)】的数据库操作Service实现
* @createDate 2026-03-10 11:40:55
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{

}




