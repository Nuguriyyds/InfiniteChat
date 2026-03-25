package com.wangyutao.offlineservice.model.dto;

import lombok.Data;

@Data
public class ReadMessageRequest {

    private Long userId;
    private String sessionId;
    private Long lastReadSeq;
}
