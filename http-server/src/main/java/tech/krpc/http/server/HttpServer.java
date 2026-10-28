package tech.krpc.http.server;

import jakarta.annotation.PreDestroy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import java.util.concurrent.TimeUnit;
import java.net.InetSocketAddress;
import tech.krpc.util.EnvUtils;
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
    // BIND-HOST: the bound server channel, retained so the actual listen address is observable
    // (its localAddress reflects the resolved KRPC_BIND_HOST / wildcard bind). null until start().
    Channel serverChannel;

    // AUD-omp-52: a connection that sends no inbound bytes within this window is reaped, so a
    // slow-loris "open and abandon" client can no longer pin a worker forever. Generous enough
    // that normal (sub-second) handlers are never affected; a per-request wall-clock cap is a
    // separate concern (O6/timeout), not this idle reaper.
    static final int READ_IDLE_SECONDS = 60;

    public void start() throws Exception {

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(Math.max(6,Runtime.getRuntime().availableProcessors() * 3));

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                //.handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch)  {
                        ChannelPipeline p = ch.pipeline();
                        // AUD-omp-52: idle reaper first, so it observes raw read activity. Fires an
                        // IdleStateEvent after READ_IDLE_SECONDS with no inbound read; the handler
                        // (AbstractHttpHandler.userEventTriggered) closes the channel on that event.
                        p.addLast("idle", new IdleStateHandler(READ_IDLE_SECONDS, 0, 0, TimeUnit.SECONDS));
                        p.addLast("decoder", new HttpRequestDecoder());
                        p.addLast("encoder", new HttpResponseEncoder());
                        p.addLast("aggregator", new HttpObjectAggregator(1048576));
                        p.addLast("handler", handler);
                    }
                }).childOption(ChannelOption.SO_KEEPALIVE, true);

            // BIND-HOST: unset KRPC_BIND_HOST (or -Drpc.server.bindHost) => EXACTLY the previous
            // wildcard bind(port). Set => bind that address, same host as the gRPC face.
            String bindHost = EnvUtils.bindHost();
            ChannelFuture f = (bindHost == null
                    ? b.bind(port)
                    : b.bind(new InetSocketAddress(bindHost, port))).sync();
            serverChannel = f.channel();
           ////Asynchronous monitoring
            //f.channel()
            //    .closeFuture()
            //    .awaitUninterruptibly();
        } catch (Exception e) {
            // AUD-omp-52: bind (or pipeline setup) failed AFTER both groups were allocated.
            // Previously they leaked their NIO threads; a caller retry loop then re-allocated fresh
            // groups each attempt, stacking orphaned thread pools. Shut them down before rethrowing
            // so a retry starts clean.
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            throw e;
        }

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