package com.wangyutao.offlineservice.model.dto;

import com.wangyutao.offlineservice.model.entity.AppMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PullOfflineMessageResponse {

    private List<AppMessage> messages;
    private Boolean hasMore;
    private Long total;
    private Long currentTime;
}
