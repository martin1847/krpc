/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.server.invoke;

import tech.krpc.model.RpcResult;

/**
 *
 * @author Martin.C
 * @version 2021/11/08 2:20 PM
 */
@FunctionalInterface
public interface MethodCall<DTO> {
    RpcResult<DTO> call(Object input) throws Throwable;
}