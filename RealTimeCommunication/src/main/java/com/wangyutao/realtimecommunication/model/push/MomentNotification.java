package com.wangyutao.realtimecommunication.model.push;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class MomentNotification {

    private List<Long> receiveUserIds;

    private Integer noticeType;

    private String avatar;

    private Integer total;
}
