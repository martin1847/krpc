/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.jws;

import io.grpc.StatusException;

/**
 *
 * @author martin.cong
 * @version 2021-11-25 22:52
 */
@FunctionalInterface
public interface ExtVerify {

    void afterSignCheck(JwsCredential jws)  throws StatusException;

    ExtVerify EMPTY = jws -> {};

}