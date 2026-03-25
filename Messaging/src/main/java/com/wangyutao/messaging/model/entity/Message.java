package com.wangyutao.messaging.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * IM核心聊天消息表
 * @TableName im_message
 */
@TableName(value ="im_message")
@Data
@Accessors(chain = true)
public class Message {
    /**
     * 消息ID (分布式雪花算法 String)
     */
    @TableId
    private String messageId;

    /**
     * 会话ID
     */

    private String clientMsgId;

    private Long seq;


    private String sessionId;

    /**
     * 发送者ID
     */
    private Long senderId;

    /**
     * 接收者ID (群聊可为空)
     */
    private Long receiverId;

    /**
     * 消息类型: 1文本, 2图片, 3文件, 4视频, 5红包, 6表情包
     */
    private Integer messageType;

    /**
     * 会话类型: 1单聊, 2群聊
     */
    private Integer sessionType;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 回复ID (非回复消息则为空)
     */
    private String replyId;

    /**
     * 消息状态: 0正常, 1撤回, 2删除
     */
    private Integer status;

    /**
     * 发送时间 (精确到毫秒)
     */
    private Date createTime;

    /**
     * 最后更新时间
     */
    private Date updateTime;

}