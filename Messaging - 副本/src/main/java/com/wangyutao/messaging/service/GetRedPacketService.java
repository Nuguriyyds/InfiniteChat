package com.wangyutao.messaging.service;

import com.wangyutao.messaging.model.dto.RedPacketResponse;
import org.springframework.stereotype.Service;


public interface GetRedPacketService {
    RedPacketResponse getRedPacketDetails(Long redPacketId, Integer pageNum, Integer pageSize);
}
