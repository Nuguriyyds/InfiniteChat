package com.wangyutao.authenticationservice.utils;

import com.wangyutao.authenticationservice.loderBalance.LoadBalancer;
import com.wangyutao.authenticationservice.model.enums.ConfigEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceInstanceUtil {

    private final DiscoveryClient discoveryClient;
    private final LoadBalancer loadBalancer;

    /**
     * 根据一致性哈希选择 RTC 节点，并直接返回客户端可用的 RTC WebSocket 直连地址。
     * RTC 节点自己的 WebSocket 协议、端口、路径来自注册中心 metadata，而不是由 Auth 本地硬编码。
     * 不再写入 im:route，路由注册完全由 RTC 的 WebSocket 握手管理。
     */
    public String getWebSocketUrl(Long userId) {
        List<ServiceInstance> instances = discoveryClient.getInstances(ConfigEnum.NETTY_SERVER.getValue());

        if (instances == null || instances.isEmpty()) {
            log.error("Nacos 中没有找到可用的 RTC 实例 [{}]", ConfigEnum.NETTY_SERVER.getValue());
            return null;
        }

        ServiceInstance instance = loadBalancer.select(instances, userId);
        if (instance == null) {
            log.error("用户 [{}] 的 RTC 节点选择结果为空", userId);
            return null;
        }

        String webSocketUrl = buildWebSocketUrl(instance);
        if (!StringUtils.hasText(webSocketUrl)) {
            log.error("用户 [{}] 选中的 RTC 实例缺少有效的 WebSocket metadata: serviceId={}, host={}, port={}, metadata={}",
                    userId,
                    instance.getServiceId(),
                    instance.getHost(),
                    instance.getPort(),
                    instance.getMetadata());
            return null;
        }

        log.info("用户 [{}] 被分配至 RTC 节点: serviceId={}, httpUri={}, wsUrl={}",
                userId,
                instance.getServiceId(),
                instance.getUri(),
                webSocketUrl);
        return webSocketUrl;
    }

    private String buildWebSocketUrl(ServiceInstance instance) {
        Map<String, String> metadata = instance.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }

        String protocol = normalizeProtocol(metadata.get(ConfigEnum.NETTY_WS_PROTOCOL_METADATA_KEY.getValue()));
        String port = metadata.get(ConfigEnum.NETTY_WS_PORT_METADATA_KEY.getValue());
        String path = normalizePath(metadata.get(ConfigEnum.NETTY_WS_PATH_METADATA_KEY.getValue()));

        if (!StringUtils.hasText(protocol) || !StringUtils.hasText(port) || !StringUtils.hasText(path)) {
            return null;
        }

        try {
            Integer.parseInt(port);
        } catch (NumberFormatException e) {
            log.error("RTC 实例 WebSocket 端口 metadata 非法: host={}, metadata={}", instance.getHost(), metadata, e);
            return null;
        }

        return protocol + "://" + instance.getHost() + ":" + port + path;
    }

    private String normalizeProtocol(String protocol) {
        if (!StringUtils.hasText(protocol)) {
            return null;
        }

        String normalized = protocol.trim();
        if (normalized.endsWith("://")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }

        String normalized = path.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
