package com.wangyutao.messaging.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wangyutao.messaging.model.entity.BalanceLog;
import com.wangyutao.messaging.service.BalanceLogService;
import com.wangyutao.messaging.mapper.BalanceLogMapper;
import org.springframework.stereotype.Service;

/**
* @author yutao
* @description 针对表【balance_log(用户资金变动流水表)】的数据库操作Service实现
* @createDate 2026-03-11 08:59:49
*/
@Service
public class BalanceLogServiceImpl extends ServiceImpl<BalanceLogMapper, BalanceLog>
    implements BalanceLogService{

}




