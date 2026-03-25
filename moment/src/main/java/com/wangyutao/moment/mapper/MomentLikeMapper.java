package com.wangyutao.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.moment.model.entity.MomentLike;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MomentLikeMapper extends BaseMapper<MomentLike> {

    @Select("SELECT COUNT(*) FROM moment_like WHERE moment_id = #{momentId} AND user_id = #{userId}")
    int existsLike(@Param("momentId") Long momentId, @Param("userId") Long userId);

    @Select("<script>" +
            "SELECT moment_id FROM moment_like WHERE user_id = #{userId} AND moment_id IN " +
            "<foreach collection='momentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Long> selectLikedMomentIds(@Param("userId") Long userId, @Param("momentIds") List<Long> momentIds);
}
