package com.wangyutao.realtimecommunication.model.push;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class NewSessionNotification {

    private String userId;

    private String sessionId;

    // 1 单聊，2 群聊
    private Integer sessionType;

    // 注意区分是申请者还是接受者名字
    private String sessionName;

    // 注意区分是申请者还是接受者头像
    private String avatar;
}
