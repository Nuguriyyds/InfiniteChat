package com.wangyutao.authenticationservice.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@Data
@TableName("user_balance")
@Accessors(chain = true)
public class UserBalance {

    /**
     * 用户ID
     */
    @TableId("user_id")
    private String userId;

    /**
     * 余额
     */
    @TableField("balance")
    private BigDecimal balance;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}