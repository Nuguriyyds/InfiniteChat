package com.wangyutao.messaging.constants;

import java.math.BigDecimal;

/**
 * 红包相关的常量定义。
 */
public enum RedPacketConstants {
    RED_PACKET_KEY_PREFIX("red_packet:count:"),
    RECEIVED_SET_KEY_PREFIX("red_packet:received:"),
    PENDING_AMOUNT_KEY_PREFIX("red_packet:pending:"),
    /**
     * 四合一抢红包 Lua 脚本：原子完成防重 + 扣库存 + 弹金额 + 写暂存
     * KEYS[1]=已领取 Set, KEYS[2]=库存 count key, KEYS[3]=金额 list key, KEYS[4]=pending hash key
     * ARGV[1]=userId
     * 返回值: "-1"=已领取, "0"=已抢完, 其他=抢到的金额字符串
     */
    UNIFIED_GRAB_LUA(
        "if redis.call('sismember', KEYS[1], ARGV[1]) == 1 then return '-1' end " +
        "local count = redis.call('get', KEYS[2]) " +
        "if count == false or tonumber(count) <= 0 then return '0' end " +
        "local amount = redis.call('lpop', KEYS[3]) " +
        "if amount == false then return '0' end " +
        "redis.call('decr', KEYS[2]) " +
        "redis.call('sadd', KEYS[1], ARGV[1]) " +
        "redis.call('hset', KEYS[4], ARGV[1], amount) " +
        "return amount"
    ),
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
