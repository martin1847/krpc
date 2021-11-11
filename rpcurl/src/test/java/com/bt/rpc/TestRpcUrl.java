/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc;

import org.junit.jupiter.api.Test;

/**
 *
 * @author Martin.C
 * @version 2021/11/10 11:41 AM
 */
public class TestRpcUrl {

    @Test
    public void testUrl() throws Exception {
        if("yyc".equals(System.getenv("LOGNAME"))) {
            RpcUrl url = new RpcUrl();
            url.localhost = true;

            //url.url = new URL("https://example-api.botaoyx.com");

            url.app = "admin-auth";
            url.service = RpcUrl.META_SERVICE;
            url.method = "listApis";

            url.run();
        }

    }
}