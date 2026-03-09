package com.wangyutao.realtimecommunication.model.push;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class NewGroupSessionNotification {

    private String creatorId;

    private String sessionId;

    // 1 单聊，2 群聊
    private Integer sessionType;

    private String sessionName;

    private String avatar;
}