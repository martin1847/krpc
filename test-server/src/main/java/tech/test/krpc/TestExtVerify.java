/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package tech.test.krpc;

import jakarta.enterprise.context.ApplicationScoped;

import tech.krpc.server.jws.ExtVerify;
import tech.krpc.server.jws.JwsCredential;
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