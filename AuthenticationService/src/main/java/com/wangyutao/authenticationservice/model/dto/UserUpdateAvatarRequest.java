package com.wangyutao.authenticationservice.model.dto;

import javax.validation.constraints.NotEmpty;

public class UserUpdateAvatarRequest {
    // AvatarUrl 头像地址
    @NotEmpty(message = "头像地址不能为空")
    public String avatarUrl;

}
