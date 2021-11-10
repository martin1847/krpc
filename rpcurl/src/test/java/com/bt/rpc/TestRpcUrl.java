/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc;

import java.net.URL;

import org.junit.jupiter.api.Test;

/**
 *
 * @author Martin.C
 * @version 2021/11/10 11:41 AM
 */
public class TestRpcUrl {

    @Test
    public void testUrl() throws Exception {
        RpcUrl url = new RpcUrl();
        //url.localhost = true;

        url.url = new URL("example-api.botaoyx.com");

        url.app = "demo-java-server";
        url.service = RpcUrl.META_SERVICE;
        url.method = "listApis";


        url.run();

    }
}