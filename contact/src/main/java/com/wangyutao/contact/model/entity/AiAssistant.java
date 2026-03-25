package com.wangyutao.contact.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 助手配置实体
 */
@Data
@TableName("ai_assistant")
public class AiAssistant {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 用户 ID
     */
    private Long userId;
    
    /**
     * AI 助手 ID（也是 contact_id）
     */
    private Long assistantId;
    
    /**
     * AI 助手名称
     */
    private String assistantName;
    
    /**
     * AI 助手头像
     */
    private String assistantAvatar;
    
    /**
     * AI 模型类型
     */
    private String modelType;
    
    /**
     * AI 人设（JSON）
     */
    private String personality;
    
    /**
     * 上下文记忆条数
     */
    private Integer contextLimit;
    
    /**
     * 状态（0=禁用 1=启用）
     */
    private Integer status;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
