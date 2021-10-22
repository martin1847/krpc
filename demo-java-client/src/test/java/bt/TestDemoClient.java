/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package bt;

import com.bt.TestJavaProxyClient;
import com.bt.rpc.common.RpcConstants;
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
            TestJavaProxyClient.test("127.0.0.1", RpcConstants.DEFAULT_PORT,false);

           // TestJavaProxyClient.test("example-api.botaoyx.com", 443,true);
        }else{
            logger.info("Skip Test Server");
        }
    }
}