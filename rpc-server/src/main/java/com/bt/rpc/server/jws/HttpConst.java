/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.jws;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 10:16 AM
 */
public interface HttpConst {


    String COOKIE_HEADER = "cookie";

    String CLIENT_ID_HEADER = "c-id";


    //https://redmine.botaoyx.com/projects/bt/wiki/%E5%85%A8%E5%B1%80header
    String CLIENT_META_HEADER = "c-meta";

    String AUTHORIZATION_HEADER = "authorization";

    //String COOKIE_TOKEN = "c-token";

    //Authorization: Bearer <token.jwt.data>
    String BEARER_FLAG = "Bearer";


    String ADM_CLAIM = "adm";
}