package com.wangyutao.moment.model.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class CommentVO {
    
    private Long commentId;
    
    private Long userId;
    
    private String username;
    
    private String avatar;
    
    private String content;
    
    private Long replyToUserId;
    
    private String replyToUsername;
    
    private LocalDateTime createdAt;
}
