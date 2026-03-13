package com.wangyutao.authenticationservice.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

@TableName(value ="user")
@Data
@Accessors(chain = true)
public class User implements Serializable {
    /**
     * id
     */
    @TableId
    private Long userId;

    /**
     * 用户昵称
     */
    private String userName;


    /**
     * 密码
     */
    private String password;


    private String email;


    /**
     * 手机号
     */
    private String phone;

    /**
     * 用户头像
     */
    private String avatar;


    /**
     * 个性签名
     */
    private String signature;


    /**
     * 性别 0 男 1 女
     */
    private Integer gender;




    private Integer status;

    /**
     * 创建时间
     */
    private Date createdAt;

    /**
     * 更新时间
     */
    private Date updatedAt;


    @TableField(exist = false)
    private static final long serialVersionUID = 1L;


}
