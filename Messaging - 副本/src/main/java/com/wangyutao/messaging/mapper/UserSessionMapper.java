package com.wangyutao.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.messaging.model.entity.Message;
import com.wangyutao.messaging.model.entity.UserSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {

}
