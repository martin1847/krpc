/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package tech.krpc.server.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import tech.krpc.server.jws.ExtVerify;
import tech.krpc.server.jws.JwsCredential;
import io.grpc.StatusException;
import io.quarkus.arc.DefaultBean;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2022/10/31 13:37
 */
@ApplicationScoped
@DefaultBean
@Slf4j
@Named("EmptyExtVerify")
public class EmptyExtVerify implements ExtVerify {
    @Override
    public void afterSignCheck(JwsCredential jws, boolean isCookie) throws StatusException {

    }
}