package com.wangyutao.realtimecommunication.websocket;

import cn.hutool.json.JSONUtil;
import com.wangyutao.realtimecommunication.enums.ClientMessageTypeEnum;
import com.wangyutao.realtimecommunication.model.entity.MessageDTO;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class NettyServer {

    @Value("${netty.port}")
    private int port;

    @Value("${netty.ws-path}")
    private String webSocketPath;

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(NettyRuntime.availableProcessors());
    private final EventExecutorGroup businessGroup = new DefaultEventExecutorGroup(NettyRuntime.availableProcessors() * 2);

    private final MessageInboundHandler messageInboundHandler;
    private final WebSocketTokenAuthHandler webSocketTokenAuthHandler;
    private final NettySessionManager sessionManager;

    @PostConstruct
    public void start() {
        new Thread(() -> {
            try {
                log.info("Netty 即将启动，绑定端口: {}, wsPath: {}", port, webSocketPath);
                run();
            } catch (InterruptedException e) {
                log.error("Netty 启动被中断", e);
                Thread.currentThread().interrupt();
            }
        }, "Netty-Boss-Thread").start();
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
                    protected void initChannel(SocketChannel socketChannel) {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast(new IdleStateHandler(5 * 60, 0, 0));
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new ChunkedWriteHandler());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(webSocketTokenAuthHandler);
                        pipeline.addLast(new WebSocketServerProtocolHandler(webSocketPath));
                        pipeline.addLast(businessGroup, messageInboundHandler);
                    }
                });

        Channel serverChannel = serverBootstrap.bind(port).sync().channel();
        log.info("Netty WebSocket 服务启动成功，端口: {}, path: {}", port, webSocketPath);
        serverChannel.closeFuture().sync();
    }

    @PreDestroy
    public void destroy() {
        log.info("开始优雅停机流程");

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        sessionManager.getUserChannelMap().forEach((userId, channel) -> {
            if (channel.isActive()) {
                MessageDTO shutdownMsg = new MessageDTO()
                        .setType(ClientMessageTypeEnum.LOG_OUT.getCode())
                        .setData("服务器维护中，请稍后重连");
                channel.writeAndFlush(new TextWebSocketFrame(JSONUtil.toJsonStr(shutdownMsg)));
            }
        });

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Future<?> futureBoss = bossGroup.shutdownGracefully();
        Future<?> futureWorker = workerGroup.shutdownGracefully();
        Future<?> futureBusiness = businessGroup.shutdownGracefully();
        futureBoss.syncUninterruptibly();
        futureWorker.syncUninterruptibly();
        futureBusiness.syncUninterruptibly();

        log.info("优雅停机完成");
    }
}
