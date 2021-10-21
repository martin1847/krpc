package com.bt;

import com.bt.demo.TimeReq;
import com.bt.demo.TimeService;
import com.bt.demo.service.HelloBean;
import com.bt.rpc.client.CacheManager;
import com.bt.rpc.client.GeneralizeClient;
import com.bt.rpc.client.RpcClientFactory;
import com.bt.rpc.client.SimpleLRUCache;
import com.bt.rpc.common.RpcConstants;
import com.bt.rpc.common.RpcMetaService;
import com.bt.rpc.demo.filter.TestFilter;
import com.bt.rpc.util.JsonUtils;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * TODO change this comment
 * 2020-01-02 17:41
 *
 * @author Martin.C
 */
@Slf4j
public class TestJavaProxyClient {


    static final String SERVER_APP = "demo-java-server";

    private final ManagedChannel channel;
    private final RpcClientFactory builder;


    /** Construct client connecting to HelloWorld server at {@code host:port}. */
    public TestJavaProxyClient(String host, int port) {
        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        CacheManager cacheManager =  new SimpleLRUCache();
        builder = new RpcClientFactory(SERVER_APP,channel,cacheManager);
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    /** Say hello to server. */
    public void greet(TimeService timeService,  String name) {
        try {




//            var timeService2 = builder.get(TimeService.class);
//
//            timeService = timeService2;
//            System.out.println("time ping" + timeService.ping());
            System.out.println("time ping 1" + timeService.ping1());
            System.out.println("time ping 2" + timeService.ping2());
            Thread.sleep(100L);
            System.out.println("time ping 2" + timeService.ping2());
            System.out.println("time ping 3" + timeService.ping3());


            System.out.println("hello1 : " + timeService.hello(new TimeReq("Java",2020)));
            Thread.sleep(100L);
            System.out.println("hello2 : " + timeService.hello(new TimeReq("Java",2020)));


            //
            //testException(timeService,500);
            //
            //testException(timeService,1000);
            //
            //testException(timeService,2000);
            //
            //testException(timeService,5000);
            //
            //testException(timeService,10000);
            //
            //testException(timeService,20000);
            System.out.println("time pingWithRuntimeException 4 : "
                    + timeService.pingWithRuntimeException());





//            HelloService greeterService = builder.get(HelloService.class);
//            var response = greeterService.hello(request, request2);
//
//
//
//            logger.info("Greeting:------------ " + response.getData());
//
//            logger.info("Hello2:------------ " + greeterService.hello2());




        } catch (Exception e) {
            log.error( "RPC failed with greeting ", e);
        }
    }

    public void testException(TimeService timeService, int times){
        long b = System.currentTimeMillis();
        for (int i = 0; i < times; i++) {
            try {
                timeService.pingWithRuntimeException();
            }catch (Exception e){
                // ignore
            }
        }
        var avgCost = (System.currentTimeMillis() - b) / (double)times;
        System.out.println(times + " Exception , avgCost :" + avgCost +" ms");

    }

    /**
     * Greet server. If provided, the first element of {@code args} is the name to use in the
     * greeting.
     */
    public static void test(String host) throws Exception {
        TestJavaProxyClient client = new TestJavaProxyClient(host, RpcConstants.DEFAULT_PORT);
        try {
            /* Access a service running on the local machine on port 50051 */


            var inputJson="{\"name\":\"JavaGener\",\"age\":2020}";

            var fullHelloName = SERVER_APP+"/"+TimeService.class.getSimpleName()+"/hello";

            var msg = GeneralizeClient.call(client.channel, fullHelloName,inputJson);

            System.out.println(
                    GeneralizeClient.toJson(msg)
            );




            var timeService = client.builder.get(TimeService.class, Collections.singletonList(new TestFilter()));


            var request = new HelloBean("Test1",18);


            var rpcObj = timeService.hello(new TimeReq("Java",2020));
            System.out.println(
                    JsonUtils.stringify(
                            rpcObj.getData()
                    )
            );

            System.out.println(rpcObj);
            System.out.println( "hello :  " + rpcObj);


            var metaService = client.builder.get(RpcMetaService.class);
            System.out.println(
                    JsonUtils.stringify(
                    metaService.listApis().getData()
                    )
            );

            client.greet(timeService, "world ! ");



        } finally {
            client.shutdown();
        }
    }

    //public static void main(String[] args) throws Exception {
    //    test("127.0.0.1");
    //}
}
