package test.client;

import java.util.List;
import java.util.concurrent.TimeUnit;

import tech.krpc.client.CacheManager;
import tech.krpc.client.ClientFilter;
import tech.krpc.client.RpcClientFactory;
import tech.krpc.client.SimpleLRUCache;
import tech.krpc.common.RpcConstants;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/**
 * 2020-01-02 17:41
 *
 * @author Martin.C
 */
public class TestRpcClient {


    public static final String SERVER_APP = "test-server";

    private final ManagedChannel channel;
    private final RpcClientFactory builder;
    public TestRpcClient(){
        this("127.0.0.1", RpcConstants.DEFAULT_PORT);
    }

    public TestRpcClient(String host, int port) {
        this(host,port,false);
    }
    /** Construct client connecting to HelloWorld server at {@code host:port}. */
    public TestRpcClient(String host, int port, boolean tls) {
        var channelBuilder = ManagedChannelBuilder.forAddress(host, port);
        if(tls){
            channelBuilder.useTransportSecurity();
        }else {
            channelBuilder.usePlaintext();
        }
        channel =       channelBuilder .build();
        CacheManager cacheManager =  new SimpleLRUCache();
        builder = new RpcClientFactory(SERVER_APP,channel,cacheManager);
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public <T> T getService(Class<T> typeClass, List<ClientFilter> filterList) {
        return this.builder.get(typeClass, filterList);
    }
}
