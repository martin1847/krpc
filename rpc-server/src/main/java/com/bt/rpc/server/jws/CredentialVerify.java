/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.jws;

import io.grpc.StatusException;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 1:52 PM
 */
//@FunctionalInterface
public interface CredentialVerify {

    UserCredential verify(String token,String cid,boolean isCookie) throws StatusException;

    String getCookieName();

}