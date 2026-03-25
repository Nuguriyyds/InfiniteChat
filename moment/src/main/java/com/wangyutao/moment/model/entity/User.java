package com.wangyutao.moment.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@TableName(value ="user")
@Data
public class User {
    @TableId
    private Long userId;

    private String userName;

    private String avatar;

    private String signature;

    private Integer gender;

    private Integer status;

    private Date createdAt;

    private Date updatedAt;
}
