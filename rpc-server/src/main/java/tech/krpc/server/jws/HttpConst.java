/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.server.jws;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 10:16 AM
 */
public interface HttpConst {


    String COOKIE_HEADER = "cookie";

    String CLIENT_ID_HEADER = "c-id";


    // Global client metadata header (caller-supplied, opaque to the server).
    String CLIENT_META_HEADER = "c-meta";

    String AUTHORIZATION_HEADER = "authorization";

    //String COOKIE_TOKEN = "c-token";

    //Authorization: Bearer <token.jwt.data>
    String BEARER_FLAG = "Bearer";


    String ADM_CLAIM = "adm";
}