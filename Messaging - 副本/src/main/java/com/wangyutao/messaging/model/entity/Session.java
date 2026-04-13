package com.wangyutao.messaging.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("im_session")
public class Session {

    @TableId
    private String sessionId;

    private Integer sessionType;

    private String name;

    private String avatar;

    private Long creatorId;

    private Date createTime;

    private Date updateTime;
}