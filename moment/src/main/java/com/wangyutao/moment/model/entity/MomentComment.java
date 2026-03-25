package com.wangyutao.moment.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TableName("moment_comment")
@Accessors(chain = true)
public class MomentComment {

    @TableId("comment_id")
    private Long commentId;

    @TableField("moment_id")
    private Long momentId;

    @TableField("user_id")
    private Long userId;

    @TableField("content")
    private String content;

    @TableField("reply_to_user_id")
    private Long replyToUserId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
