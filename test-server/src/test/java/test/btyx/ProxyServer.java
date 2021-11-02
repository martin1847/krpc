package test.btyx;

import com.bt.demo.impl.DemoServiceImpl;
import com.bt.rpc.common.RpcConstants;
import com.bt.rpc.server.RpcServerBuilder;
import io.grpc.Server;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * test server
 * 2020-01-02 16:24
 *
 * @author Martin.C
 */

public class ProxyServer {
    private Server server;

    private static final Logger logger = LoggerFactory.getLogger(ProxyServer.class);

    private int port = RpcConstants.DEFAULT_PORT;
    private void start() throws Exception {
        RpcServerBuilder proxyServerBuilder = new RpcServerBuilder.Builder("demo-java-server")
                //.addService(new ServerApp.GreeterImpl())
                .addService(new DemoServiceImpl())
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

    @Test
    public void testServer() throws Exception {
        if("yyc".equals(System.getenv("LOGNAME"))
        || "young".equals(System.getenv("LOGNAME"))
        ) {
            final ProxyServer server = new ProxyServer();
            server.start();
    //        GrpcMain.invoke();
            server.blockUntilShutdown();
        }else{
            logger.info("Skip Test Server");
        }
    }
}
