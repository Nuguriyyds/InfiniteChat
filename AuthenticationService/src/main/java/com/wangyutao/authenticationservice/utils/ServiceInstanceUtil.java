package com.wangyutao.authenticationservice.utils;


import com.wangyutao.authenticationservice.loderBalance.LoadBalancer;
import com.wangyutao.authenticationservice.model.enums.ConfigEnum;
import com.wangyutao.authenticationservice.model.enums.TimeOutEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceInstanceUtil {

    private final DiscoveryClient discoveryClient;
    private final StringRedisTemplate redisTemplate;

    // 🌟 核心改变：直接注入咱们写好的单例高性能负载均衡器！
    // 只要你刚才写的 ConsistentHashLoadBalancer 上加了 @Component，Spring 就会自动把它塞进来
    private final LoadBalancer loadBalancer;

    public String getServiceInstance(String userId) {
        // 1. 从 Nacos 动态拉取当前所有存活的 Netty 实例列表
        List<ServiceInstance> instances = discoveryClient.getInstances(ConfigEnum.NETTY_SERVER.getValue());

        if (instances == null || instances.isEmpty()) {
            log.error("❌ 致命异常：Nacos 中没有找到可用的 Netty 实例 [{}]！", ConfigEnum.NETTY_SERVER.getValue());
            return null;
        }

        // 🌟 2. 极速路由寻址！直接调用单例的 select 方法
        ServiceInstance instance = loadBalancer.select(instances, userId);

        if (instance == null) {
            return null;
        }

        // 3. 将分配结果持久化到 Redis (供后续“推送服务”跨节点寻址使用)
        String redisKey = ConfigEnum.NETTY_SERVER_HEAD.getValue() + userId;
        String nettyUri = instance.getUri().toString();

        redisTemplate.opsForValue().set(redisKey, nettyUri, TimeOutEnum.TOKEN_TIME_OUT.getTimeOut(), TimeUnit.DAYS);
        log.info("✅ 用户 [{}] 被分配至 Netty 节点: {}", userId, nettyUri);

        // 返回主机 IP 给 UserServiceImpl 拼接完整 WebSocket 地址
        return instance.getHost();
    }
}
