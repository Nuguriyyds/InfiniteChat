package com.wangyutao.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.messaging.model.entity.Friend;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FriendMapper extends BaseMapper<Friend> {

    @Select("SELECT status FROM contact WHERE user_id = #{userId} AND contact_id = #{friendId}")
    Friend selectFriendship(@Param("userId") Long userId, @Param("friendId") Long friendId);
}