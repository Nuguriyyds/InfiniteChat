package com.wangyutao.messaging.model.enums;

import lombok.Getter;

/**
 * 会话类型枚举
 */
@Getter
public enum SessionType {

    SINGLE(1, "单聊"),
    GROUP(2, "群聊");

    private final int value;
    private final String desc;

    SessionType(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public static SessionType fromValue(int value) {
        for (SessionType type : SessionType.values()) {
            if (type.getValue() == value) {
                return type;
            }
        }
        return null; // 或者抛出异常
    }
}
