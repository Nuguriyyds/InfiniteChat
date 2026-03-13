package com.wangyutao.messaging.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("im_user_session")
public class UserSession {

    @TableId
    private Long id;

    private String sessionId;

    private Long userId;

    /**
     * 1-群主, 2-管理员, 3-普通成员
     */
    private Integer roleType;

    private Integer status;

    private Date createTime;
}