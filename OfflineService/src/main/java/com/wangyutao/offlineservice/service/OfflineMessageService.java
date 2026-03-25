package com.wangyutao.offlineservice.service;

import com.wangyutao.offlineservice.model.dto.PullOfflineMessageRequest;
import com.wangyutao.offlineservice.model.dto.PullOfflineMessageResponse;

public interface OfflineMessageService {

    PullOfflineMessageResponse pullOfflineMessages(PullOfflineMessageRequest request);

    void storeOfflineMessage(Long receiverId, String messageJson);
}
