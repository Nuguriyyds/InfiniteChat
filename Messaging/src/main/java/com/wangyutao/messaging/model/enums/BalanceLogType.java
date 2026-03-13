package com.wangyutao.messaging.model.enums;

/**
 * 余额日志类型枚举。
 */
public enum BalanceLogType {
    SEND_RED_PACKET(1, "发送红包"),
    RECEIVE_RED_PACKET(2, "领取红包"),
    REFUND_RED_PACKET(3, "红包退回");

    private final int type;
    private final String description;

    BalanceLogType(int type, String description) {
        this.type = type;
        this.description = description;
    }

    public int getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }
}
