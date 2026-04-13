package com.wangyutao.messaging.mapper;

import com.wangyutao.messaging.model.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
* @author yutao
* @description 针对表【user(用户核心表)】的数据库操作Mapper
* @createDate 2026-03-10 11:40:55
* @Entity com.wangyutao.messaging.model.entity.User
*/
@Mapper
public interface UserMapper extends BaseMapper<User> {

}




