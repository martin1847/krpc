package com.bt.rpc.server.ext;

import com.bt.rpc.common.RpcConstants;
import io.grpc.ServerProvider;

/**
 * 2021/8/19 2:48 PM
 *
 * @author Martin.C
 */
public class GraalvmBuild {

    static {
        System.out.println("[ RPC Server For GraalvmBuild ] Static ServerProvider :" + RpcConstants.CI_BUILD_ID+" , " + ServerProvider.provider());
        //System.out.println("ENV : \n" + System.getenv());
        //System.out.println("PROP : \n" + System.getProperties());
    }

    static public void forNative(){

    }

}
