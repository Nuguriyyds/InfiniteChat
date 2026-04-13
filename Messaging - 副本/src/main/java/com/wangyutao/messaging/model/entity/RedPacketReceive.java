package com.wangyutao.messaging.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import lombok.Data;

/**
 * 红包领取记录表
 * @TableName red_packet_receive
 */
@TableName(value ="red_packet_receive")
@Data
public class RedPacketReceive {
    /**
     * 记录ID (雪花算法发号)
     */
    @TableId
    private Long redPacketReceiveId;

    /**
     * 红包ID
     */
    private Long redPacketId;

    /**
     * 领取者用户ID
     */
    private Long receiverId;

    /**
     * 领取金额
     */
    private BigDecimal amount;

    /**
     * 领取时间
     */
    @TableField("received_at")
    private LocalDateTime receivedAt;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        RedPacketReceive other = (RedPacketReceive) that;
        return (this.getRedPacketReceiveId() == null ? other.getRedPacketReceiveId() == null : this.getRedPacketReceiveId().equals(other.getRedPacketReceiveId()))
            && (this.getRedPacketId() == null ? other.getRedPacketId() == null : this.getRedPacketId().equals(other.getRedPacketId()))
            && (this.getReceiverId() == null ? other.getReceiverId() == null : this.getReceiverId().equals(other.getReceiverId()))
            && (this.getAmount() == null ? other.getAmount() == null : this.getAmount().equals(other.getAmount()))
            && (this.getReceivedAt() == null ? other.getReceivedAt() == null : this.getReceivedAt().equals(other.getReceivedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getRedPacketReceiveId() == null) ? 0 : getRedPacketReceiveId().hashCode());
        result = prime * result + ((getRedPacketId() == null) ? 0 : getRedPacketId().hashCode());
        result = prime * result + ((getReceiverId() == null) ? 0 : getReceiverId().hashCode());
        result = prime * result + ((getAmount() == null) ? 0 : getAmount().hashCode());
        result = prime * result + ((getReceivedAt() == null) ? 0 : getReceivedAt().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", redPacketReceiveId=").append(redPacketReceiveId);
        sb.append(", redPacketId=").append(redPacketId);
        sb.append(", receiverId=").append(receiverId);
        sb.append(", amount=").append(amount);
        sb.append(", receivedAt=").append(receivedAt);
        sb.append("]");
        return sb.toString();
    }
}