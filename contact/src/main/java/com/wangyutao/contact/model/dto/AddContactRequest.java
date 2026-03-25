package com.wangyutao.contact.model.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 添加好友请求
 */
@Data
public class AddContactRequest {
    
    /**
     * 用户 ID（从 Header 获取）
     */
    private Long userId;
    
    /**
     * 联系人 ID
     */
    @NotNull(message = "联系人 ID 不能为空")
    private Long contactId;
    
    /**
     * 备注名
     */
    private String remark;
}
