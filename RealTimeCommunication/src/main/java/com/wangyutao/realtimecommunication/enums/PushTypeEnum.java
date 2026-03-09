package com.wangyutao.realtimecommunication.enums;

import lombok.Getter;

@Getter
public enum PushTypeEnum {

    //新会话通知
    NEW_SESSION_NOTIFICATION(1),

    //消息通知
    MESSAGE_NOTIFICATION(2),

    //朋友圈通知
    MOMENT_NOTIFICATION(3),

    //好友申请通知
    FRIEND_APPLICATION_NOTIFICATION(4);

    private final int code;

    PushTypeEnum(int code) {
        this.code = code;
    }

    public static PushTypeEnum fromCode(int code) {
        for (PushTypeEnum type : PushTypeEnum.values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid PushType code: " + code);
    }
}
