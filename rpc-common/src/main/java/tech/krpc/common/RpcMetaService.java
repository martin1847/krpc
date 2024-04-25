package tech.krpc.common;

import java.util.List;

import tech.krpc.annotation.RpcService;
import tech.krpc.common.meta.ApiMeta;
import tech.krpc.model.RpcResult;

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
