package com.wangyutao.offlineservice.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GatewayPushPacket {

    private List<Long> targetUserIds;
    private String wsPayload;
}
