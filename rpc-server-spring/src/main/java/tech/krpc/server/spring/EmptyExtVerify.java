/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package tech.krpc.server.spring;

import io.grpc.StatusException;
import tech.krpc.server.jws.ExtVerify;
import tech.krpc.server.jws.JwsCredential;

/**
 *
 * @author Martin.C
 * @version 2022/10/31 13:37
 */
//@Slf4j
//@Named("EmptyExtVerify")
//@Primary
//@ConditionalOnMissingBean(ExtVerify.class)
public class EmptyExtVerify implements ExtVerify {
    @Override
    public void afterSignCheck(JwsCredential jws, boolean isCookie) throws StatusException {
    }
}