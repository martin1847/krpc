package com.bt.rpc.common.meta;

import com.bt.rpc.common.RpcConstants;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class
ApiMeta {

    String app;

    List<Api> apis;
    List<Dto> dtos;


    /// rpc-SDK version
    /// `major-version.minor-version`
    /// [semantic versioning](http://semver.org)
    ///  Zero major versions must only be used for experimental, non-GA interfaces.
    String sdkVersion = RpcConstants.VERSION;

    /// >0 if enable
    int restCallPort;

    String vendor = RpcConstants.VENDOR;

    // the api your package offer
    String apiVersion;

    String buildVersion = RpcConstants.CI_BUILD_ID;

    public ApiMeta(String appName, List<Api> apis, List<Dto> dtos) {
        this.app = appName;
        this.apis = apis;
        this.dtos = dtos;
    }
}