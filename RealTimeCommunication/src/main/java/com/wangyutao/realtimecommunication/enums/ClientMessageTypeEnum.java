package com.wangyutao.realtimecommunication.enums;

import lombok.Getter;

@Getter
public enum ClientMessageTypeEnum {

    // ACK 确认消息
    ACK(1),

    // LOG_OUT 退出登录
    LOG_OUT(2),

    // HEART_BEAT 心跳
    HEART_BEAT(5),

    // ILLEGAL 非法
    ILLEGAL(99);


    // code 消息类型
    private final Integer code;

    /*
     * 构造函数
     * */
    ClientMessageTypeEnum(Integer code){
        this.code = code;
    }

    /*
     * 获取消息类型
     * */
    public static ClientMessageTypeEnum of(Integer type) {

        switch (type) {
            case 1:
                return ClientMessageTypeEnum.ACK;
            case 2:
                return ClientMessageTypeEnum.LOG_OUT;
            case 5:
                return ClientMessageTypeEnum.HEART_BEAT;
            default:
                return ClientMessageTypeEnum.ILLEGAL;
        }
    }
}
