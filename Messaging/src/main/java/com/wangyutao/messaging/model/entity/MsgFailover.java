package com.wangyutao.messaging.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 本地消息兜底表
 * 对应表：im_msg_failover
 */
@TableName("im_msg_failover")
@Data
@Accessors(chain = true)
public class MsgFailover {

    @TableId
    private Long id;

    /**
     * 客户端消息唯一标识
     */
    private String clientMsgId;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 发送到 MQ 的完整 JSON 载荷
     */
    private String payload;

    /**
     * 处理状态：0-未处理，1-已重新投递，-1-死信（超过最大重试次数）
     */
    private Integer status;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    @TableField("created_at")
    private Date createTime;

    @TableField("updated_at")
    private Date updateTime;
}

