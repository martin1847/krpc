/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package test.client;

import java.util.Arrays;

import tech.krpc.client.AsyncClient;
import tech.krpc.client.AsyncMethod;
import tech.krpc.client.AsyncMethod.ResultObserver;
import tech.krpc.model.RpcResult;
import tech.test.krpc.DemoService;
import tech.test.krpc.dto.TimeReq;
import tech.test.krpc.dto.TimeResult;
import tech.test.krpc.dto.Book;

/**
 *
 * @author Martin.C
 * @version 2022/01/07 2:50 PM
 */
public class TestDemoService {
    public static void main(String[] args) throws InterruptedException {

        var client = new TestRpcClient();

        var demoService = client.getService(DemoService.class,null);

        System.out.println("bytesTime : "+ Arrays.toString(demoService.bytesTime().getData()));

        System.out.println(demoService.bytesSum(new byte[]{1,2,3,4,5}));

        System.out.println("bytesInc: "+ Arrays.toString(demoService.incBytes(new byte[]{1,2,3,4,5}).getData()));

        var asyncClient = new AsyncClient<>(demoService);
        testAsync(asyncClient,"save",new Book(11,"async"));
        testAsync(asyncClient,"save",new Book(22,"async"));
        testAsync(asyncClient,"inc100",100);

        AsyncMethod<DemoService, TimeReq, TimeResult> hello = AsyncMethod.from(demoService,"hello");
        var req = new TimeReq();
        req.setAge(1000);
        hello.call(req);
        AsyncMethod<DemoService, Object, Integer> testException = AsyncMethod.from(demoService,"testRuntimeException");
        testException.call(null);




        Thread.sleep(2000);
    }

    static <DTO> void testAsync(AsyncClient client,String method,Object param){
        var b = System.currentTimeMillis();
        client.call(method,param,new ResultObserver<DTO>(){
            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onSuccess(RpcResult<DTO> res) {
                System.out.println( Thread.currentThread().getName() +  " |  async call res :"+res);
            }
        });
        System.out.println(Thread.currentThread().getName() + "| async call "+(System.currentTimeMillis() -b));
    }
}