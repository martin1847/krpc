/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.bt.rpc.server;

import com.bt.rpc.common.RpcConstants;
import com.bt.rpc.common.MService;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.util.EnvUtils;

/**
 *
 * @author Martin.C
 * @version 2022/02/25 1:38 PM
 */
class MServiceImpl implements MService {
    @Override
    public RpcResult<String> v() {
        return RpcResult.ok(RpcConstants.CI_BUILD_ID + "  , " + EnvUtils.hostName());
    }

    @Override
    public RpcResult<String> h() {
        return RpcResult.ok(ServerContext.current().getHeaders().toString());
    }
}