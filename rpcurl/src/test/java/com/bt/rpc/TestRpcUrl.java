/**
 * Martin.Cong
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
        if("yyc".equals(System.getenv("LOGNAME"))) {
            RpcUrl url = new RpcUrl();
            //url.localhost = true;

            url.url = new URL("https://backoffice-api.botaoyx.com");

            url.app = "admin-auth";
            url.service = "Oauth";
            url.method = "user";

            url.cookie = "c-token=eyJhbGciOiJFUzI1NiIsImtpZCI6ImJvLXRlc3QtMjExMSJ9.eyJzdWIiOiIxMDAxIiwiYWRtIjoxLCJleHAiOjE2MzcyMDExNTh9.xBtc8IcAp7rQcIkAVlhLz5u6dbN2WRnANQOyam6qKWBy0YvyDU0WcvnaEA5k2KzVhnLylqIQY4F5GJSy3aTaRQ";

            url.run();
        }

    }
}