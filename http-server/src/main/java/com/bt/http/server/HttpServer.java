package com.bt.http.server;

import jakarta.annotation.PreDestroy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import lombok.extern.slf4j.Slf4j;

//@ApplicationScoped
//@Startup
@Slf4j
public class HttpServer {



    final ChannelHandler handler;
    final int port;
    public HttpServer(ChannelHandler handler) {
        this(handler,8080);
    }

    public HttpServer(ChannelHandler handler, int port) {
        this.handler = handler;
        this.port = port;
    }

    EventLoopGroup bossGroup ,workerGroup ;

    public void start() throws Exception {

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(Math.max(6,Runtime.getRuntime().availableProcessors() * 3));

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            //.handler(new LoggingHandler(LogLevel.INFO))
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch)  {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast("decoder", new HttpRequestDecoder());
                    p.addLast("encoder", new HttpResponseEncoder());
                    p.addLast("aggregator", new HttpObjectAggregator(1048576));
                    p.addLast("handler", handler);
                }
            }).childOption(ChannelOption.SO_KEEPALIVE, true);


        ChannelFuture f = b.bind(port).sync();
       ////Asynchronous monitoring
        //f.channel()
        //    .closeFuture()
        //    .awaitUninterruptibly();

    }

    @PreDestroy
    public void shutdown() {
        log.info("***** shutdown http server ...");
        if(null != bossGroup)
        bossGroup.shutdownGracefully();
        if(null != workerGroup)
        workerGroup.shutdownGracefully();
    }
}