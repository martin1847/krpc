package com.bt.rpc.common.meta;

import com.bt.rpc.common.RpcConstants;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ApiMeta {
    List<Api> apis;
    List<Dto> dtos;

    String sdkVersion = RpcConstants.VERSION;

    /// >0 if enable
    int restCallPort;

    String vendor = RpcConstants.VENDOR;


    /// api version
    /// `major-version.minor-version`
    /// [semantic versioning](http://semver.org)
    ///  Zero major versions must only be used for experimental, non-GA interfaces.
    public String version;

    public ApiMeta(List<Api> apis, List<Dto> dtos) {
        this.apis = apis;
        this.dtos = dtos;
    }
}