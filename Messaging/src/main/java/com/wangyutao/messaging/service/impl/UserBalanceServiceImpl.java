package com.wangyutao.messaging.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wangyutao.messaging.model.entity.UserBalance;
import com.wangyutao.messaging.service.UserBalanceService;
import com.wangyutao.messaging.mapper.UserBalanceMapper;
import org.springframework.stereotype.Service;

/**
* @author yutao
* @description 针对表【user_balance(用户钱包余额表)】的数据库操作Service实现
* @createDate 2026-03-10 11:41:57
*/
@Service
public class UserBalanceServiceImpl extends ServiceImpl<UserBalanceMapper, UserBalance>
    implements UserBalanceService{

}




