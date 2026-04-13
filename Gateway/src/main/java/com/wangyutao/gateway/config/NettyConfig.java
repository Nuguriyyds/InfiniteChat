package com.wangyutao.gateway.config;

import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.resources.LoopResources;

@Configuration
public class NettyConfig {

    private static final int MIN_IO_THREADS = 8;

    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> nettyCustomizer() {
        int ioThreads = Math.max(Runtime.getRuntime().availableProcessors() * 2, MIN_IO_THREADS);
        return factory -> factory.addServerCustomizers(server ->
                // Gateway is a pure proxy here; pinning it to 4 loops causes request queuing under load.
                server.runOn(LoopResources.create("gateway-io", ioThreads, true))
        );
    }
}
