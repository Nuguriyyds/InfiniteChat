package com.wangyutao.moment.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class PublishMomentRequest {
    
    private String content;
    
    private List<String> images;
}
