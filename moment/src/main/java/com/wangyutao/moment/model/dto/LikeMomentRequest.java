package com.wangyutao.moment.model.dto;

import lombok.Data;

@Data
public class LikeMomentRequest {
    
    private Long momentId;
    
    private Long userId;
}
