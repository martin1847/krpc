package com.bt.rpc.common;

import java.util.List;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.common.meta.ApiMeta;
import com.bt.rpc.model.RpcResult;

/**
 * 2020-01-19 16:05
 *
 * @author Martin.C
 */
@RpcService
public interface RpcMetaService {

    RpcResult<ApiMeta> listApis();

    /**
     * 支持的序列化方式
     */
    RpcResult<List<String>> serials();
}
