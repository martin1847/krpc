/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.btyx.rpc;

import com.bt.rpc.server.jws.JwsVerify;
import io.grpc.StatusException;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 1:28 PM
 */
public class TestJwt {

    public static void main(String[] args) {
        //var url = TestJwt.class.getResource("/jwks.json").toString();
        //var vefiy = new JwsVerify(url);
        //var token = "eyJraWQiOiJiby10ZXN0LTIxMTEiLCJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIxMjM0IiwiYWRtIjoxLCJleHAiOjE2MzcwNzkwMzN9.B-BsfO6THnHD-7z3afSZm4vhZVrigM0EpwdZ3VvcTbXoktSur8-2TRdWuUl9Hdgyfg9TvhdoR9UNPXK6VPddlw";
        //try {
        //    vefiy.verify(token, "xxx",false);
        //}catch (StatusException e){
        //    e.printStackTrace();
        //}
        String[] a = new String[2];
        Object[] b = a;
        a[0] = "hi";
        b[1] = Integer.valueOf(42);
        System.out.println(b[1]);
        System.out.println(a[1]);
    }

}