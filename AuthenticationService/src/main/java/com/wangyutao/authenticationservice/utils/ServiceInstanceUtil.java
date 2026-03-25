package com.wangyutao.authenticationservice.utils;


import com.wangyutao.authenticationservice.loderBalance.LoadBalancer;
import com.wangyutao.authenticationservice.model.enums.ConfigEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceInstanceUtil {

    private final DiscoveryClient discoveryClient;
    private final StringRedisTemplate redisTemplate;
    private final LoadBalancer loadBalancer;

    /**
     * 根据一致性哈希选择 Netty 节点，返回节点 Host IP。
     * 不再写入 im:route，路由注册完全由 RTC 的 WebSocket 握手管理。
     */
    public String getServiceInstance(Long userId) {
        List<ServiceInstance> instances = discoveryClient.getInstances(ConfigEnum.NETTY_SERVER.getValue());

        if (instances == null || instances.isEmpty()) {
            log.error("致命异常：Nacos 中没有找到可用的 Netty 实例 [{}]！", ConfigEnum.NETTY_SERVER.getValue());
            return null;
        }

        ServiceInstance instance = loadBalancer.select(instances, userId);

        if (instance == null) {
            return null;
        }

        log.info("用户 [{}] 被分配至 Netty 节点: {}", userId, instance.getUri());

        return instance.getHost();
    }
}
