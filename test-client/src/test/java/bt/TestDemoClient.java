/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package bt;

import com.bt.TestJavaProxyClient;
import com.btyx.test.UserService;
import com.btyx.test.dto.User;
import com.bt.rpc.common.RpcConstants;
import com.bt.model.PagedQuery;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author martin.cong
 * @version 2021-10-17 15:05
 */

public class TestDemoClient {

    private static final Logger logger = LoggerFactory.getLogger(TestDemoClient.class);

    @Test
    public void testClient() throws Exception {
        if("yyc".equals(System.getenv("LOGNAME"))
                || "young".equals(System.getenv("LOGNAME"))
        ) {
            var client = new TestJavaProxyClient("127.0.0.1", RpcConstants.DEFAULT_PORT);

            //var client = new TestJavaProxyClient("example-api.botaoyx.com", 443,true);

            var userService = client.getService(UserService.class,null);

            var u1 =            userService.getUser(1);
            System.out.println(u1.getData().getName());

            var user = new User();
            user.setName("test");

            var pl = userService.listUser(new PagedQuery<>(2,3,user)).getData();
            System.out.println(pl.getCount() +" \n " + pl.getData());

            user.setId(8);
            System.out.println(
                    userService.saveUser(user)
            );

            client.test();

        }else{
            logger.info("Skip Test Server");
        }
    }
}