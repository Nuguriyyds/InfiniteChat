package com.wangyutao.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.moment.model.entity.Moment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MomentMapper extends BaseMapper<Moment> {

    /**
     * 与 Contact 模块一致：好友在表 {@code contact}（非 im_friend）。
     * 包含本人动态 + contact_type=0 且 status=1 的好友动态。
     */
    @Select("SELECT m.* FROM moment m " +
            "WHERE m.user_id = #{userId} " +
            "   OR EXISTS ( " +
            "     SELECT 1 FROM contact f " +
            "     WHERE f.user_id = #{userId} " +
            "       AND f.contact_id = m.user_id " +
            "       AND f.status = 1 " +
            "       AND f.contact_type = 0 " +
            "   ) " +
            "ORDER BY m.created_at DESC " +
            "LIMIT #{offset}, #{limit}")
    List<Moment> selectFriendMoments(@Param("userId") Long userId,
                                      @Param("offset") Integer offset,
                                      @Param("limit") Integer limit);

    @Update("UPDATE moment SET like_count = like_count + 1 WHERE moment_id = #{momentId}")
    int incrementLikeCount(@Param("momentId") Long momentId);

    @Update("UPDATE moment SET like_count = like_count - 1 WHERE moment_id = #{momentId} AND like_count > 0")
    int decrementLikeCount(@Param("momentId") Long momentId);

    @Update("UPDATE moment SET comment_count = comment_count + 1 WHERE moment_id = #{momentId}")
    int incrementCommentCount(@Param("momentId") Long momentId);
}
