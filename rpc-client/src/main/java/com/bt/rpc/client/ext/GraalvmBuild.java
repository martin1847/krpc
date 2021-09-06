package com.bt.rpc.client.ext;

import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannelProvider;
import io.grpc.NameResolverRegistry;

/**
 * 2021/8/19 2:38 PM
 *
 * @author Martin.C
 */
public class GraalvmBuild {
    // just for graalVM build time init
    static {
        System.out.println("[ RPC Client For GraalvmBuild ] ManagedChannelProvider : " + ManagedChannelProvider.provider());
        System.out.println("[ RPC Client For GraalvmBuild ] NameResolverRegistry   : " + NameResolverRegistry.getDefaultRegistry());
        System.out.println("[ RPC Client For GraalvmBuild ] LoadBalancerRegistry   : " + LoadBalancerRegistry.getDefaultRegistry());
    }
}
