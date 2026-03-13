package com.wangyutao.realtimecommunication.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

@Data
@Accessors(chain = true)
public class OnlineUserVo implements Serializable {

    // 当前在线总人数
    private int onlineCount;

    // 具体的在线用户明细列表
    private List<OnlineUserInfo> onlineUserList;

    /**
     * 内部类：极简的在线用户信息
     * ⚠️ 绝对不要在这里面放 io.netty.channel.Channel 对象！
     */
    @Data
    @Accessors(chain = true)
    public static class OnlineUserInfo implements Serializable {
        // 用户 ID

        @JsonSerialize(using = ToStringSerializer.class) // 防止 JS 精度丢失
        private Long userId;

        // 对应的 Netty 管道短 ID (前端排查日志用的)
        private String channelShortId;

        // 还可以扩展：比如用户的登录 IP 等 (以后从 Channel 里面取)
        // private String clientIp;
    }
}
