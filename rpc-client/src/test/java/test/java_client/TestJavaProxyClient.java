package test.java_client;

import com.bt.demo.TimeReq;
import com.bt.demo.TimeService;
import com.bt.demo.service.HelloBean;
import com.bt.rpc.client.CacheManager;
import com.bt.rpc.client.GeneralizeClient;
import com.bt.rpc.client.RpcClientFactory;
import com.bt.rpc.client.SimpleLRUCache;
import com.bt.rpc.common.RpcConstants;
import com.bt.rpc.common.RpcMetaService;
import com.bt.rpc.util.SerializationUtils;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TODO change this comment
 * 2020-01-02 17:41
 *
 * @author Martin.C
 */
public class TestJavaProxyClient {
    private static final Logger logger = Logger.getLogger(TestJavaProxyClient.class.getName());

    private final ManagedChannel channel;
    private final RpcClientFactory builder;


    /** Construct client connecting to HelloWorld server at {@code host:port}. */
    public TestJavaProxyClient(String host, int port) {
        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        CacheManager cacheManager =  new SimpleLRUCache();
        builder = new RpcClientFactory(channel,cacheManager);
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    /** Say hello to server. */
    public void greet(String name) {
        try {

            HelloBean request = new HelloBean("Test1",18);
            HelloBean request2 = new HelloBean("Test2",19);


            var timeService = builder.get(TimeService.class, Collections.singletonList(new TestFilter()));
            System.out.println("hello" + timeService.hello(new TimeReq("Java",2020)));
            Thread.sleep(100L);
            System.out.println("hello" + timeService.hello(new TimeReq("Java",2020)));

            System.out.println("time ping" + timeService.ping());
            System.out.println("time ping 1" + timeService.ping1());
            System.out.println("time ping 2" + timeService.ping2());
            Thread.sleep(100L);
            System.out.println("time ping 2" + timeService.ping2());
            System.out.println("time ping 3" + timeService.ping3());
            System.out.println("time ping 4" + timeService.pingWithRuntimeException());


//            HelloService greeterService = builder.get(HelloService.class);
//            var response = greeterService.hello(request, request2);
//
//
//
//            logger.info("Greeting:------------ " + response.getData());
//
//            logger.info("Hello2:------------ " + greeterService.hello2());




        } catch (Exception e) {
            logger.log(Level.WARNING, "RPC failed", e);
            return;
        }
    }

    /**
     * Greet server. If provided, the first element of {@code args} is the name to use in the
     * greeting.
     */
    public static void main(String[] args) throws Exception {
        TestJavaProxyClient client = new TestJavaProxyClient("localhost", RpcConstants.DEFAULT_PORT);
        try {
            /* Access a service running on the local machine on port 50051 */
            String user = "world";
            if (args.length > 0) {
                user = args[0]; /* Use the arg as the name to greet if provided */
            }
            client.greet(user);

            var inputJson="{\"name\":\"JavaGener\",\"age\":2020}";

            var msg = GeneralizeClient.call(client.channel,TimeService.class.getName(),"hello",inputJson);

            System.out.println(
                    GeneralizeClient.toJson(msg)
            );

            var metaService = client.builder.get(RpcMetaService.class);
            System.out.println(
                    SerializationUtils.toJson(
                    metaService.listApis().getData()
                    )
            );




        } finally {
            client.shutdown();
        }
    }
}
