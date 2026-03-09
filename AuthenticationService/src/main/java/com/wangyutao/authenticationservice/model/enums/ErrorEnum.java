package com.wangyutao.authenticationservice.model.enums; // 换成你的包名

public enum ErrorEnum {
    // 成功状态码
    SUCCESS(20000, "ok"),

    // 客户端错误 (400xx 级别)
    PARAMS_ERROR(40000, "请求参数错误"),
    CODE_ERROR(40001, "验证码错误"),
    NOT_REGISTER_ERROR(40002, "未注册, 用户不存在"),
    REGISTER_ERROR(40003, "注册失败, 用户已存在"),
    RESET_PWD_ERROR(40004, "重置密码失败, 用户不存在"),
    PASSWORD_ERROR(40005, "密码错误"),
    LOGIN_ERROR(40006, "登录失败, 手机号或密码错误"),
    NOT_FOUND_ERROR(40400, "请求数据不存在"),

    // 🌟 权限与认证错误 (401xx, 403xx 级别)
    NOT_LOGIN_ERROR(40100, "未登录或 Token 非法"),
    LOGIN_EXPIRED(40101, "登录已过期，请重新登录"), // 给咱们的双 Token 续期用的！
    NO_AUTH_ERROR(40300, "无权限"),
    FORBIDDEN_ERROR(40301, "禁止访问"),

    // 服务端异常 (500xx 级别)
    SYSTEM_ERROR(50000, "系统内部异常"),
    OPERATION_ERROR(50001, "操作失败"),
    GET_LOCK_ERROR(50002, "获取分布式锁失败，请稍后重试"), // 给你的注册接口加的
    MYSQL_ERROR(50003, "数据库操作异常"), // 给你的 MyBatis Plus 报错加的
    UPDATE_AVATAR_ERROR(50004, "更新头像失败"),
    SERVICE_ERROR(50005, "服务异常");

    /**
     * 业务状态码
     */
    private final int code;

    /**
     * 错误信息
     */
    private final String msg; // 改叫 msg，和咱们前面的 BusinessException、ResultUtils 统一

    ErrorEnum(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}