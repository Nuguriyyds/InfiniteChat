package com.wangyutao.realtimecommunication.websocket;

import com.wangyutao.realtimecommunication.enums.ConfigEnum;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NettySessionManagerTest {

    @Test
    void shouldPublishCurrentNodeLoadWhenUserComesOnline() {
        NettySessionManager manager = new NettySessionManager();

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        ReflectionTestUtils.setField(manager, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(manager, "localHost", "10.0.0.8");
        ReflectionTestUtils.setField(manager, "nettyPort", 9101);
        ReflectionTestUtils.setField(manager, "wsProtocol", "ws");
        ReflectionTestUtils.setField(manager, "wsPath", "/api/v1/chat/message");

        manager.initializeCurrentNodeLoad();

        EmbeddedChannel channel = new EmbeddedChannel();
        manager.add(10001L, channel);

        verify(zSetOperations).add(ConfigEnum.RTC_NODE_LOAD_ZSET.getValue(), "ws://10.0.0.8:9101/api/v1/chat/message", 0D);
        verify(zSetOperations).add(ConfigEnum.RTC_NODE_LOAD_ZSET.getValue(), "ws://10.0.0.8:9101/api/v1/chat/message", 1D);
    }

    @Test
    void shouldCleanRouteAndRefreshLoadWhenTrackedChannelDisconnects() {
        NettySessionManager manager = new NettySessionManager();

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("im:route:10001")).thenReturn("NODE_1");

        ReflectionTestUtils.setField(manager, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(manager, "localNodeId", "NODE_1");
        ReflectionTestUtils.setField(manager, "localHost", "10.0.0.8");
        ReflectionTestUtils.setField(manager, "nettyPort", 9101);
        ReflectionTestUtils.setField(manager, "wsProtocol", "ws");
        ReflectionTestUtils.setField(manager, "wsPath", "/api/v1/chat/message");

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(WebSocketTokenAuthHandler.USER_ID_KEY).set(10001L);

        manager.add(10001L, channel);
        manager.remove(channel);

        verify(redisTemplate).delete("im:route:10001");
        verify(zSetOperations, times(1))
                .add(eq(ConfigEnum.RTC_NODE_LOAD_ZSET.getValue()), eq("ws://10.0.0.8:9101/api/v1/chat/message"), eq(0D));
        verify(zSetOperations).add(ConfigEnum.RTC_NODE_LOAD_ZSET.getValue(), "ws://10.0.0.8:9101/api/v1/chat/message", 1D);
    }
}
