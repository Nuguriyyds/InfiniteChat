package com.wangyutao.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.moment.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
