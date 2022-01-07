/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package test.client;

import com.bt.rpc.client.AsyncClient;
import com.bt.rpc.client.AsyncClient.ResultObserver;
import com.bt.rpc.model.RpcResult;
import com.btyx.test.DemoService;
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
        var b = System.currentTimeMillis();
        asyncClient.call("save",new User(11,"async"),new ResultObserver(){

            @Override
            public void onError(Throwable t) {

            }

            @Override
            public void onSuccess(RpcResult res) {
                System.out.println("async call res :"+res);
            }
        });
        System.out.println("async call "+(System.currentTimeMillis() -b));
        Thread.sleep(1000);
    }
}