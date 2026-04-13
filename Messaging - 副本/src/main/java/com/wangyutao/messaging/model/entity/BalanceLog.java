package com.wangyutao.messaging.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * 用户资金变动流水表
 * @TableName balance_log
 */
@TableName(value ="balance_log")
@Data
public class BalanceLog {
    /**
     * 流水ID
     */
    @TableId
    private Long balanceLogId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 变动金额 (正数表示增加，负数表示减少)
     */
    private BigDecimal amount;

    /**
     * 类型：1-发红包扣款，2-抢红包入账，3-红包退回
     */
    private Integer type;

    /**
     * 关联业务ID (比如具体的红包ID)
     */
    private Long relatedId;

    /**
     * 变动时间
     */
    private Date createdAt;

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
        BalanceLog other = (BalanceLog) that;
        return (this.getBalanceLogId() == null ? other.getBalanceLogId() == null : this.getBalanceLogId().equals(other.getBalanceLogId()))
            && (this.getUserId() == null ? other.getUserId() == null : this.getUserId().equals(other.getUserId()))
            && (this.getAmount() == null ? other.getAmount() == null : this.getAmount().equals(other.getAmount()))
            && (this.getType() == null ? other.getType() == null : this.getType().equals(other.getType()))
            && (this.getRelatedId() == null ? other.getRelatedId() == null : this.getRelatedId().equals(other.getRelatedId()))
            && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getBalanceLogId() == null) ? 0 : getBalanceLogId().hashCode());
        result = prime * result + ((getUserId() == null) ? 0 : getUserId().hashCode());
        result = prime * result + ((getAmount() == null) ? 0 : getAmount().hashCode());
        result = prime * result + ((getType() == null) ? 0 : getType().hashCode());
        result = prime * result + ((getRelatedId() == null) ? 0 : getRelatedId().hashCode());
        result = prime * result + ((getCreatedAt() == null) ? 0 : getCreatedAt().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", balanceLogId=").append(balanceLogId);
        sb.append(", userId=").append(userId);
        sb.append(", amount=").append(amount);
        sb.append(", type=").append(type);
        sb.append(", relatedId=").append(relatedId);
        sb.append(", createdAt=").append(createdAt);
        sb.append("]");
        return sb.toString();
    }
}