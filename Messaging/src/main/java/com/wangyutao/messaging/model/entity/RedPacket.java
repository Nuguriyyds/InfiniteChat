package com.wangyutao.messaging.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 红包主表
 */
@Data
@TableName("red_packet")
@Accessors(chain = true)
public class RedPacket {

    /**
     * 红包ID
     */
    @TableId("red_packet_id")
    private Long redPacketId;

    /**
     * 发送者用户ID
     */
    @TableField("sender_id")
    private Long senderId;

    /**
     * 会话ID（单聊或群聊）
     */
    @TableField("session_id")
    private Long sessionId;

    /**
     * 红包封面文案
     */
    @TableField("red_packet_wrapper_text")
    private String redPacketWrapperText;

    /**
     * 红包类型：1普通红包，2拼手气红包
     */
    @TableField("red_packet_type")
    private Integer redPacketType;

    /**
     * 红包总金额
     */
    @TableField("total_amount")
    private BigDecimal totalAmount;

    /**
     * 红包总个数
     */
    @TableField("total_count")
    private Integer totalCount;

    /**
     * 剩余金额
     */
    @TableField("remaining_amount")
    private BigDecimal remainingAmount;

    /**
     * 剩余个数
     */
    @TableField("remaining_count")
    private Integer remainingCount;

    /**
     * 状态：1未领取完，2已领取完，3已过期
     */
    @TableField("status")
    private Integer status;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("version")
    private Integer version;
}
