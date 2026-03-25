package com.wangyutao.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangyutao.moment.model.entity.MomentComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MomentCommentMapper extends BaseMapper<MomentComment> {

    @Select("SELECT * FROM moment_comment WHERE moment_id = #{momentId} ORDER BY created_at ASC LIMIT #{limit}")
    List<MomentComment> selectTopComments(@Param("momentId") Long momentId, @Param("limit") Integer limit);

    /**
     * 每条动态前 N 条评论（MySQL 8+ 窗口函数），避免列表页 N 次查询。
     */
    @Select("<script>" +
            "SELECT comment_id, moment_id, user_id, content, reply_to_user_id, created_at FROM (" +
            "  SELECT c.comment_id, c.moment_id, c.user_id, c.content, c.reply_to_user_id, c.created_at, " +
            "         ROW_NUMBER() OVER (PARTITION BY c.moment_id ORDER BY c.created_at ASC) AS rn " +
            "  FROM moment_comment c " +
            "  WHERE c.moment_id IN " +
            "  <foreach collection='momentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            ") t WHERE rn &lt;= #{limit} " +
            "ORDER BY moment_id, created_at ASC" +
            "</script>")
    List<MomentComment> selectTopCommentsBatch(@Param("momentIds") List<Long> momentIds, @Param("limit") int limit);
}
