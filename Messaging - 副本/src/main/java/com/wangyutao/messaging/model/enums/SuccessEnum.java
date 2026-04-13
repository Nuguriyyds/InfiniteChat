package com.wangyutao.messaging.model.enums;

import lombok.Getter;

@Getter
public enum SuccessEnum {
    SUCCESS_LOGOUT("退出成功"),
    SUCCESS_REGISTER("注册成功"),
    SUCCESS_PASSWORD_CHANGE("密码修改成功"),
    SUCCESS_CODE_SEND("验证码已发送"),
    SUCCESS_RESET_PWD("密码重置成功"),
    SUCCESS_LOGIN("登录成功");

    private String message;

    SuccessEnum(String message) {
        this.message = message;
    }

}
