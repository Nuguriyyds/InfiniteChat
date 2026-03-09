package com.wangyutao.realtimecommunication.websocket;


import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class NettyServer {

    @Value("${netty.port}")
    private int port;

    // boss
    private EventLoopGroup bossGroup = new NioEventLoopGroup(1);

    // worker
    private EventLoopGroup workerGroup = new NioEventLoopGroup(NettyRuntime.availableProcessors());

    private EventExecutorGroup businessGroup = new DefaultEventExecutorGroup(NettyRuntime.availableProcessors() * 2);

    // redis
    private final StringRedisTemplate redisTemplate;
//
//    private final NettySessionManager sessionManager;

    private final MessageInboundHandler messageInboundHandler;

    private final WebSocketTokenAuthHandler webSocketTokenAuthHandler;

    // discoveryClient
    private final DiscoveryClient discoveryClient;

    @PostConstruct
    public void start() throws InterruptedException {
        //System.out.println("=============== 读取到的 Netty 端口是：" + port + " ===============");
        run();
    }

    public void run() throws InterruptedException {
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast(new IdleStateHandler(5 * 60, 0, 0));
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new ChunkedWriteHandler());
                        pipeline.addLast(new HttpObjectAggregator(8192));
                        pipeline.addLast(webSocketTokenAuthHandler);
                        pipeline.addLast(new WebSocketServerProtocolHandler("/api/v1/chat/message"));
                        pipeline.addLast(businessGroup, messageInboundHandler);
                    }
                });

        serverBootstrap.bind(port).sync();
    }

    @PreDestroy
    public void destroy() {
        Future<?> futureBoss = bossGroup.shutdownGracefully();
        Future<?> futureWorker = workerGroup.shutdownGracefully();
        Future<?> futureBusiness = businessGroup.shutdownGracefully();
        futureBoss.syncUninterruptibly();
        futureWorker.syncUninterruptibly();
        futureBusiness.syncUninterruptibly();
        log.info("关闭 ws server 成功");
    }
}
