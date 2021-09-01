package com.bt;

import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannelProvider;
import io.grpc.NameResolverRegistry;

/**
 * 2021/8/19 2:38 PM
 *
 * @author Martin.C
 */
public class RuntimeToNativeBuild {
    // just for graalVM build time init
    static {
        System.out.println(" [ RPC Client ] ManagedChannelProvider : " + ManagedChannelProvider.provider());
        System.out.println(" [ RPC Client ] NameResolverRegistry   : " + NameResolverRegistry.getDefaultRegistry());
        System.out.println(" [ RPC Client ] LoadBalancerRegistry   : " + LoadBalancerRegistry.getDefaultRegistry());
    }
}
