package com.wangyutao.authenticationservice.loderBalance;

import com.wangyutao.authenticationservice.model.enums.ConfigEnum;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class P2CLoadBalancerTest {

    @Test
    void shouldSelectLowerLoadNodeWhenTwoCandidatesExist() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        Map<String, String> heavyMetadata = new HashMap<String, String>();
        heavyMetadata.put("ws-protocol", "ws");
        heavyMetadata.put("ws-port", "9101");
        heavyMetadata.put("ws-path", "/api/v1/chat/message");

        Map<String, String> lightMetadata = new HashMap<String, String>();
        lightMetadata.put("ws-protocol", "ws");
        lightMetadata.put("ws-port", "9101");
        lightMetadata.put("ws-path", "/api/v1/chat/message");

        ServiceInstance heavy = new DefaultServiceInstance(
                "rtc-1",
                ConfigEnum.NETTY_SERVER.getValue(),
                "10.0.0.1",
                8083,
                false,
                heavyMetadata
        );
        ServiceInstance light = new DefaultServiceInstance(
                "rtc-2",
                ConfigEnum.NETTY_SERVER.getValue(),
                "10.0.0.2",
                8083,
                false,
                lightMetadata
        );

        when(zSetOperations.score(ConfigEnum.RTC_NODE_LOAD_ZSET.getValue(), "ws://10.0.0.1:9101/api/v1/chat/message"))
                .thenReturn(12D);
        when(zSetOperations.score(ConfigEnum.RTC_NODE_LOAD_ZSET.getValue(), "ws://10.0.0.2:9101/api/v1/chat/message"))
                .thenReturn(3D);

        P2CLoadBalancer loadBalancer = new P2CLoadBalancer(redisTemplate);
        ServiceInstance selected = loadBalancer.select(Arrays.asList(heavy, light), 10001L);

        assertSame(light, selected);
    }

    @Test
    void shouldFallbackToFirstInstanceWhenWsMetadataIsMissing() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        P2CLoadBalancer loadBalancer = new P2CLoadBalancer(redisTemplate);

        ServiceInstance first = new DefaultServiceInstance(
                "rtc-1",
                ConfigEnum.NETTY_SERVER.getValue(),
                "10.0.0.1",
                8083,
                false
        );
        ServiceInstance second = new DefaultServiceInstance(
                "rtc-2",
                ConfigEnum.NETTY_SERVER.getValue(),
                "10.0.0.2",
                8083,
                false
        );

        ServiceInstance selected = loadBalancer.select(Arrays.asList(first, second), 10002L);

        assertEquals(first, selected);
    }
}
