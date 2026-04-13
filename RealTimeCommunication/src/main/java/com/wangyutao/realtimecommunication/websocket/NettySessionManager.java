package com.wangyutao.realtimecommunication.websocket;

import com.wangyutao.realtimecommunication.enums.ConfigEnum;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Getter
@Slf4j
@Component
public class NettySessionManager {

    private final ConcurrentMap<Long, Channel> userChannelMap = new ConcurrentHashMap<>();

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Value("${im.node.id:NODE_1}")
    private String localNodeId;

    @Value("${spring.cloud.nacos.discovery.ip:${spring.cloud.client.ip-address:127.0.0.1}}")
    private String localHost;

    @Value("${netty.port}")
    private int nettyPort;

    @Value("${netty.ws-protocol:ws}")
    private String wsProtocol;

    @Value("${netty.ws-path:/api/v1/chat/message}")
    private String wsPath;

    private volatile String localWsUrl;

    @PostConstruct
    public void initializeCurrentNodeLoad() {
        syncCurrentNodeLoad();
    }

    @PreDestroy
    public void removeCurrentNodeLoad() {
        redisTemplate.opsForZSet().remove(ConfigEnum.RTC_NODE_LOAD_ZSET.getValue(), getLocalWsUrl());
    }

    public void add(Long userId, Channel channel) {
        if (userId != null && channel != null) {
            userChannelMap.put(userId, channel);
            syncCurrentNodeLoad();
            log.info("User online registered, userId={}, onlineCount={}", userId, userChannelMap.size());
        }
    }

    public Channel getChannel(Long userId) {
        if (userId == null) {
            return null;
        }
        return userChannelMap.get(userId);
    }

    public void remove(Long userId) {
        if (userId != null) {
            userChannelMap.remove(userId);
            syncCurrentNodeLoad();
            log.info("Local session removed, userId={}, onlineCount={}", userId, userChannelMap.size());
        }
    }

    public void remove(Channel channel) {
        if (channel == null) {
            return;
        }

        Long userId = channel.attr(WebSocketTokenAuthHandler.USER_ID_KEY).get();
        if (userId == null) {
            return;
        }

        boolean removed = userChannelMap.remove(userId, channel);
        if (!removed) {
            log.info("Channel disconnected but local mapping has already been replaced, userId={}", userId);
            return;
        }

        String routeKey = "im:route:" + userId;
        String registeredNode = redisTemplate.opsForValue().get(routeKey);
        if (localNodeId.equals(registeredNode)) {
            redisTemplate.delete(routeKey);
            log.info("Channel disconnected and global route removed, userId={}, onlineCount={}", userId, userChannelMap.size());
        } else {
            log.info("Channel disconnected, but route is already owned by another node, userId={}, owner={}", userId, registeredNode);
        }

        syncCurrentNodeLoad();
    }

    private void syncCurrentNodeLoad() {
        redisTemplate.opsForZSet().add(
                ConfigEnum.RTC_NODE_LOAD_ZSET.getValue(),
                getLocalWsUrl(),
                userChannelMap.size()
        );
    }

    private String getLocalWsUrl() {
        if (localWsUrl == null) {
            synchronized (this) {
                if (localWsUrl == null) {
                    String protocol = StringUtils.hasText(wsProtocol) ? wsProtocol.trim() : "ws";
                    if (protocol.endsWith("://")) {
                        protocol = protocol.substring(0, protocol.length() - 3);
                    }

                    String path = StringUtils.hasText(wsPath) ? wsPath.trim() : "/api/v1/chat/message";
                    if (!path.startsWith("/")) {
                        path = "/" + path;
                    }

                    String host = StringUtils.hasText(localHost) ? localHost.trim() : "127.0.0.1";
                    localWsUrl = protocol + "://" + host + ":" + nettyPort + path;
                }
            }
        }
        return localWsUrl;
    }
}
