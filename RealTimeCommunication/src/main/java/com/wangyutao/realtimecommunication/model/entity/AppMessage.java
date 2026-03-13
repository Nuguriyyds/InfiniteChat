package com.wangyutao.realtimecommunication.model.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 🌟 MQ 异步推送 & Netty 下发专用实体类
 * 承载了前端 UI 渲染所需的所有丰富信息
 */
@Data
@Accessors(chain = true)
public class AppMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息唯一ID (带 "msg_" 前缀，必须是 String)
     */
    private String messageId;

    private Long seq;

    /**
     * 会话ID (对齐 im_message 表，使用 String)
     */
    private String sessionId;

    /**
     * 会话类型: 1单聊, 2群聊
     */
    private Integer sessionType;

    /**
     * 消息类型: 1文本, 2图片, 3文件等
     */
    private Integer type;

    /**
     * 发送者ID (后端全链路 Long，发给前端时自动转 String 防精度丢失)
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sendUserId;

    /**
     * 接收者ID集合
     * (单聊时 List 里只有 1 个元素，群聊时包含该发往该 Netty 节点的所有目标用户)
     */
    private List<Long> receiveUserIds;

    /**
     * 发送者昵称 (减少前端去查本地数据库的压力，直接随消息下发)
     */
    private String userName;

    /**
     * 发送者头像 (用于渲染左侧聊天气泡)
     */
    private String avatar;

    /**
     * 会话名称 (如群聊名称)
     */
    private String sessionName;

    /**
     * 会话头像 (如群聊九宫格头像)
     */
    private String sessionAvatar;

    /**
     * 发送时间 (格式化后的字符串，方便前端直接展示，如 "2026-03-10 12:00:00")
     */
    private String createdAt;

    /**
     * 消息具体内容
     * (可以是纯文本 String，也可以是包含图片宽高、URL 的 Map/JSON 对象)
     */
    private Object body;
}