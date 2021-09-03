package com.bt.rpc.client.ext;

import com.bt.rpc.common.RpcConstants;

public class Remote {
    public String host;

    public String serviceScan;
    public String channelFilter;

    //public GrpcChannelOptions GrpcChannelOptions

    public int port  = RpcConstants.DEFAULT_PORT;
}