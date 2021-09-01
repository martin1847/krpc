package com.bt;

import com.bt.demo.impl.MyTimeService;
import com.bt.rpc.common.RpcConstants;
import com.bt.rpc.demo.service.impl.HelloServiceImpl;
import com.bt.rpc.server.RpcServerBuilder;
import io.grpc.Server;

import java.util.logging.Logger;


/**
 * test server
 * 2020-01-02 16:24
 *
 * @author Martin.C
 */
public class ProxyServer {
    private Server server;

    private static final Logger logger = Logger.getLogger(ProxyServer.class.getName());

    private int port = RpcConstants.DEFAULT_PORT;
    private void start() throws Exception {
        RpcServerBuilder proxyServerBuilder = new RpcServerBuilder.Builder("Test Java Server")
                .addService(new HelloServiceImpl())
//                .addService(new ServerApp.GreeterImpl())
                .addService(new MyTimeService())
                //.setDiContext(new DiContextGrpcImpl())
                //.regGlobalFilter(new ExecServerFilter())
                .build();

        server = proxyServerBuilder.startServer();
        logger.info("Server started, listening on " + port);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may have been reset by its
                // JVM shutdown hook.
                System.err
                        .println("*** shutting down gRPC server since JVM is shutting down");
                ProxyServer.this.stopServer();
                System.err.println("*** server shut down");
            }
        });


    }


    private void stopServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon
     * threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    public static void main(String[] args) throws Exception {
        final ProxyServer server = new ProxyServer();
        server.start();
//        GrpcMain.invoke();
        server.blockUntilShutdown();
    }
}
