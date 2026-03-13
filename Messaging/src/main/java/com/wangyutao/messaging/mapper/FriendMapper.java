package com.wangyutao.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.messaging.model.entity.Friend;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FriendMapper extends BaseMapper<Friend> {

    // 🌟 直接用注解写 SQL，精准查询 A 和 B 的关系记录
    @Select("SELECT status FROM im_friend WHERE user_id = #{userId} AND friend_id = #{friendId}")
    Friend selectFriendship(@Param("userId") Long userId, @Param("friendId") Long friendId);
}