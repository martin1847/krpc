/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package test.client;

import com.bt.rpc.client.AsyncClient;
import com.bt.rpc.client.AsyncMethod;
import com.bt.rpc.client.AsyncMethod.ResultObserver;
import com.bt.rpc.model.RpcResult;
import com.btyx.test.DemoService;
import com.btyx.test.dto.TimeReq;
import com.btyx.test.dto.TimeResult;
import com.btyx.test.dto.User;

/**
 *
 * @author Martin.C
 * @version 2022/01/07 2:50 PM
 */
public class TestDemoService {
    public static void main(String[] args) throws InterruptedException {

        var client = new TestRpcClient();

        var demoService = client.getService(DemoService.class,null);

        System.out.println(demoService.bytesTime());

        var asyncClient = new AsyncClient<>(demoService);
        testAsync(asyncClient,"save",new User(11,"async"));
        testAsync(asyncClient,"save",new User(22,"async"));
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