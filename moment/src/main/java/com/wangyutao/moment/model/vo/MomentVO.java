package com.wangyutao.moment.model.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
public class MomentVO {
    
    private Long momentId;
    
    private Long userId;
    
    private String username;
    
    private String avatar;
    
    private String content;
    
    private List<String> images;
    
    private Integer likeCount;
    
    private Integer commentCount;
    
    private Boolean isLiked;
    
    private List<CommentVO> comments;
    
    private LocalDateTime createdAt;
}
