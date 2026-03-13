package com.wangyutao.authenticationservice.model.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;

@Data
public class UserVO implements Serializable {

    /**
     * id
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 用户昵称
     */
    private String userName;


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

    private String nettyUrl;

    /**
     * 用户token
     */
    private String Token;
    private String refreshToken;
    // 可选：加一个 accessToken 的过期时间戳，方便前端判断
    private Long expireTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;


}
