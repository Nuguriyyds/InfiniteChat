package com.wangyutao.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.messaging.model.entity.Friend;
import com.wangyutao.messaging.model.entity.Session;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionMapper extends BaseMapper<Session> {
}
