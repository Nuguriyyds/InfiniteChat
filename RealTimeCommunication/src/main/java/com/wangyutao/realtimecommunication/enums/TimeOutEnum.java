package com.wangyutao.realtimecommunication.enums;

import lombok.Getter;

@Getter
public enum TimeOutEnum {
    TOKEN_TIME_OUT("token time out(day)",3),
    JWT_TIME_OUT("token time out(day)",3),
    CODE_TIME_OUT("code time out(minute)",500000);

    private String name;

    private int timeOut;

    TimeOutEnum(String name, int timeOut) {
        this.name = name;
        this.timeOut = timeOut;
    }

}
