package com.wangyutao.moment.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TableName("moment")
@Accessors(chain = true)
public class Moment {

    @TableId("moment_id")
    private Long momentId;

    @TableField("user_id")
    private Long userId;

    @TableField("content")
    private String content;

    @TableField("images")
    private String images;

    @TableField("like_count")
    private Integer likeCount;

    @TableField("comment_count")
    private Integer commentCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
