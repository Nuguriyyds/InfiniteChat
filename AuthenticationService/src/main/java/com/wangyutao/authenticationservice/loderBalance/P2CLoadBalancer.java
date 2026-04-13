package com.wangyutao.authenticationservice.loderBalance;

import com.wangyutao.authenticationservice.model.enums.ConfigEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Slf4j
@RequiredArgsConstructor
public class P2CLoadBalancer implements LoadBalancer {

    private final StringRedisTemplate redisTemplate;

    @Override
    public ServiceInstance select(List<ServiceInstance> instances, Long userId) {
        if (instances == null || instances.isEmpty()) {
            log.warn("No available RTC instances when selecting node for userId={}", userId);
            return null;
        }

        if (instances.size() == 1) {
            return instances.get(0);
        }

        List<Candidate> candidates = new ArrayList<>(instances.size());
        for (ServiceInstance instance : instances) {
            String wsUrl = buildWebSocketUrl(instance);
            if (!StringUtils.hasText(wsUrl)) {
                continue;
            }

            Double load = redisTemplate.opsForZSet()
                    .score(ConfigEnum.RTC_NODE_LOAD_ZSET.getValue(), wsUrl);
            candidates.add(new Candidate(instance, wsUrl, load == null ? 0D : load));
        }

        if (candidates.isEmpty()) {
            log.warn("No RTC instance exposes valid ws metadata, fallback to first instance for userId={}", userId);
            return instances.get(0);
        }

        if (candidates.size() == 1) {
            return candidates.get(0).getInstance();
        }

        int firstIndex = ThreadLocalRandom.current().nextInt(candidates.size());
        int secondIndex = ThreadLocalRandom.current().nextInt(candidates.size() - 1);
        if (secondIndex >= firstIndex) {
            secondIndex++;
        }

        Candidate first = candidates.get(firstIndex);
        Candidate second = candidates.get(secondIndex);
        Candidate selected = first.getLoad() <= second.getLoad() ? first : second;

        log.info(
                "RTC node selected via P2C, userId={}, first={} load={}, second={} load={}, selected={}",
                userId,
                first.getWsUrl(),
                first.getLoad(),
                second.getWsUrl(),
                second.getLoad(),
                selected.getWsUrl()
        );
        return selected.getInstance();
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
            log.warn("Invalid RTC ws port metadata, host={}, metadata={}", instance.getHost(), metadata, e);
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

    private static final class Candidate {
        private final ServiceInstance instance;
        private final String wsUrl;
        private final double load;

        private Candidate(ServiceInstance instance, String wsUrl, double load) {
            this.instance = instance;
            this.wsUrl = wsUrl;
            this.load = load;
        }

        private ServiceInstance getInstance() {
            return instance;
        }

        private String getWsUrl() {
            return wsUrl;
        }

        private double getLoad() {
            return load;
        }
    }
}
