package com.wangyutao.realtimecommunication.enums;

import lombok.Getter;

@Getter
public enum MessageTypeEnum {
    TEXT_MESSAGE(1),
    PICTURE_MESSAGE(2),
    FILE_MESSAGE(3),
    VIDEO_MESSAGE(4),
    RED_PACKET_MESSAGE(5),
    EMOTICON_MESSAGE(6);


    private Integer code;
    MessageTypeEnum(Integer code){
        this.code = code;
    }

    public static MessageTypeEnum fromCode(int code) {
        for (MessageTypeEnum type : MessageTypeEnum.values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid code: " + code);
    }

}
