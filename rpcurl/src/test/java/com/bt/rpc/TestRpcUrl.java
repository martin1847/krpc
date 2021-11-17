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
        if("yyc".equals(System.getenv("LOGNAME"))) {
            RpcUrl url = new RpcUrl();
            //url.localhost = true;

            url.url = new URL("https://backoffice-api.botaoyx.com");

            url.app = "admin-auth";
            url.service = "Oauth";
            url.method = "user";

            url.cookie = "c-token=eyJhbGciOiJFUzI1NiIsImtpZCI6ImJvLXRlc3QtMjExMSJ9.eyJzdWIiOiIxMDAxIiwiYWRtIjoxLCJleHAiOjE2MzcxNDY2MjN9.GiXQqoNckNAn8UiXDW9BSoaPQPuS4SNJFTz1pQzmeP0PSkdo05zoYRYE2KDW6rm-eEleVEy49LK9U4g7DH5ghQ";

            url.run();
        }

    }
}