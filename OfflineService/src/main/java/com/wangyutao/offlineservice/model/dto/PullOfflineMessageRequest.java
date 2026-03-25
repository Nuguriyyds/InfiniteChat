package com.wangyutao.offlineservice.model.dto;

import lombok.Data;

import java.util.Map;

@Data
public class PullOfflineMessageRequest {

    private Long userId;
    /**
     * 单会话拉取时指定；为 null 则拉取所有会话
     */
    private String sessionId;
    /**
     * 单会话拉取时的游标
     */
    private Long lastSeq;
    /**
     * 跨会话拉取时每个会话的游标，key=sessionId, value=该会话最大已知 seq
     */
    private Map<String, Long> sessionSeqMap;
    private Integer pageSize = 50;
}
