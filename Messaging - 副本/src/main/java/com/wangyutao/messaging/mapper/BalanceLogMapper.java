package com.wangyutao.messaging.mapper;

import com.wangyutao.messaging.model.entity.BalanceLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
* @author yutao
* @description 针对表【balance_log(用户资金变动流水表)】的数据库操作Mapper
* @createDate 2026-03-11 08:59:49
* @Entity com.wangyutao.messaging.model.entity.BalanceLog
*/
@Mapper
public interface BalanceLogMapper extends BaseMapper<BalanceLog> {

}




