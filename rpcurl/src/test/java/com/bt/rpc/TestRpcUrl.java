/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc;

/**
 *
 * @author Martin.C
 * @version 2021/11/10 11:41 AM
 */
public class TestRpcUrl {

    public static void main(String[] args) {
        RpcUrl url = new RpcUrl();
        url.localhost = true;


        url.app = "demo-java-server";
        url.service = RpcUrl.META_SERVIE;
        url.method = "listApis";


        url.run();

    }
}