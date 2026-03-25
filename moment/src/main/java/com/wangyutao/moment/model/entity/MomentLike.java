package com.wangyutao.moment.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TableName("moment_like")
@Accessors(chain = true)
public class MomentLike {

    @TableId("like_id")
    private Long likeId;

    @TableField("moment_id")
    private Long momentId;

    @TableField("user_id")
    private Long userId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
