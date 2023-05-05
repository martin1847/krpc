/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.btyx.test;

import jakarta.enterprise.context.ApplicationScoped;

import com.bt.rpc.server.jws.ExtVerify;
import com.bt.rpc.server.jws.JwsCredential;
import io.grpc.StatusException;
import io.quarkus.runtime.Startup;

/**
 *
 * @author Martin.C
 * @version 2022/10/31 13:50
 */
@ApplicationScoped
@Startup
public class TestExtVerify implements ExtVerify {
    @Override
    public void afterSignCheck(JwsCredential jws, boolean isCookie) throws StatusException {

    }
}