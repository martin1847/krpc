package com.bt.rpc.server.ext;

import io.grpc.ServerProvider;

/**
 * 2021/8/19 2:48 PM
 *
 * @author Martin.C
 */
public class GraalvmBuild {

    static {
        System.out.println("[ RPC Server For GraalvmBuild ] ServerProvider :" + ServerProvider.provider());
    }

}
