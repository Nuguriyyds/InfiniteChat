package com.wangyutao.moment.model.dto;

import lombok.Data;

@Data
public class CommentMomentRequest {
    
    private Long momentId;
    
    private Long userId;
    
    private String content;
    
    private Long replyToUserId;
}
