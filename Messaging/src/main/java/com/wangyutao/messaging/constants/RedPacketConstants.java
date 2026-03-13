package com.wangyutao.messaging.constants;

import java.math.BigDecimal;

/**
 * 红包相关的常量定义。
 */
public enum RedPacketConstants {
    RED_PACKET_KEY_PREFIX("red_packet:count:"),
    RED_PACKET_LUA_SCRIPT(
            "local count = redis.call('get', KEYS[1]) " +
                    "if count == false then " +
                    "    return tonumber(0) " + //明确返回数字
                    "end " +
                    "if tonumber(count) > 0 then " +
                    "    redis.call('decr', KEYS[1]) " +
                    "    return tonumber(1) " + //明确返回数字
                    "else " +
                    "    return tonumber(2) " + //明确返回数字
                    "end"),
    RED_PACKET_TYPE_NORMAL("1"),
    RED_PACKET_TYPE_RANDOM("2"),
    WORKED_ID("1"),
    DATACENTER_ID("1"),
    MIN_AMOUNT(new BigDecimal("0.01")),
    RANDOM_MULTIPLIER(new BigDecimal("2")),
    DIVIDE_SCALE(2),
    AMOUNT_SCALE(2),
    RED_PACKET_EXPIRE_HOURS("24"), // 红包过期时间（小时）
    MAX_AMOUNT_PER_PACKET(new BigDecimal("200")), // 单个红包最大金额
    DATE_TIME_FORMAT("MM月dd日 HH:mm");


    private final Object value;

    RedPacketConstants(Object value) {
        this.value = value;
    }

    public String getValue() {
        return value.toString();
    }

    public Long getLongValue() {
        if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        return (Long) value;
    }

    public Integer getIntValue() {
        if (value instanceof String) {
            return Integer.parseInt((String) value);
        }
        return (Integer) value;
    }

    public BigDecimal getBigDecimalValue() {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return new BigDecimal(value.toString());
    }

    public Integer getDivideScale() {
        return (Integer) value;
    }

    public String getDateTimeFormat() {
        return value.toString();
    }
}
