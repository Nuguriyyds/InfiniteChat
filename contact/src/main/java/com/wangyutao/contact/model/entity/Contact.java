package com.wangyutao.contact.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 联系人实体
 */
@Data
@TableName("contact")
public class Contact {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 用户 ID
     */
    private Long userId;
    
    /**
     * 联系人 ID
     */
    private Long contactId;
    
    /**
     * 联系人类型（0=普通用户 1=AI助手 2=群聊机器人 3=系统通知）
     */
    private Integer contactType;
    
    /**
     * 备注名
     */
    private String remark;
    
    /**
     * 状态（0=已删除 1=正常 2=已拉黑）
     */
    private Integer status;
    
    /**
     * 是否置顶（0=否 1=是）
     */
    private Integer isPinned;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
