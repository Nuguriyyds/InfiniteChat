package com.wangyutao.realtimecommunication.service.impl;

import com.wangyutao.realtimecommunication.model.vo.OnlineUserVo;
import com.wangyutao.realtimecommunication.service.SysService;
import com.wangyutao.realtimecommunication.websocket.NettySessionManager;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysServiceImpl implements SysService {

    // 注入咱们的全局花名册
    private final NettySessionManager sessionManager;

    @Override
    public OnlineUserVo getOnlineUser() {
        // 1. 获取安全的 Map (Java 8 写法)
        ConcurrentMap<Long, Channel> userChannelMap = sessionManager.getUserChannelMap();

        // 2. 提取并组装前端安全的数据格式 (剔除庞大的 Channel 实例)
        List<OnlineUserVo.OnlineUserInfo> userInfoList = userChannelMap.entrySet().stream()
                .map(entry -> new OnlineUserVo.OnlineUserInfo()
                        .setUserId(entry.getKey())
                        .setChannelShortId(entry.getValue().id().asShortText()))
                .collect(Collectors.toList());

        // 3. 组装返回视图
        return new OnlineUserVo()
                .setOnlineCount(userInfoList.size())
                .setOnlineUserList(userInfoList);
    }
}
