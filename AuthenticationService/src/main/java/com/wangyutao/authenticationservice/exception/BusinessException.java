package com.wangyutao.authenticationservice.exception;

import com.wangyutao.authenticationservice.model.enums.ErrorEnum;

/**
 * 自定义异常类
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ErrorEnum errorEnum) {
        super(errorEnum.getMsg());
        this.code = errorEnum.getCode();
    }

    public BusinessException(ErrorEnum errorEnum, String message) {
        super(message);
        this.code = errorEnum.getCode();
    }

    public int getCode() {
        return code;
    }
}