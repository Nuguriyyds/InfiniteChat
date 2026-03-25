package com.wangyutao.contact.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 联系人 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContactVO {
    
    /**
     * 联系人 ID
     */
    private Long contactId;
    
    /**
     * 联系人类型（0=普通用户 1=AI助手 2=群聊机器人）
     */
    private Integer contactType;
    
    /**
     * 联系人昵称
     */
    private String nickname;
    
    /**
     * 备注名
     */
    private String remark;
    
    /**
     * 头像
     */
    private String avatar;
    
    /**
     * 状态（1=正常 2=已拉黑）
     */
    private Integer status;
    
    /**
     * 是否置顶
     */
    private Integer isPinned;
    
    /**
     * 最后消息时间
     */
    private String lastMessageTime;
}
