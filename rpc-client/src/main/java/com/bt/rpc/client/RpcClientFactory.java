package com.bt.rpc.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import com.bt.rpc.client.ext.GraalvmBuild;
import com.bt.rpc.internal.SerialEnum;
import io.grpc.ManagedChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * https://github.com/googleapis/gax-java/blob/main/gax-grpc/src/main/java/com/google/api/gax/grpc/ChannelPool.java
 *
 * todo 客户端请求非常大的时候，多开连接
 *
 * https://github.com/grpc/grpc/issues/21386
 * https://grpc.io/docs/guides/performance/#general
 *
 * (Special topic) Each gRPC channel uses 0 or more HTTP/2 connections and each connection usually has a limit on the number of
 * concurrent streams. When the number of active RPCs on the connection reaches this limit, additional RPCs are queued in the client and
 * must wait for active RPCs to finish before they are sent. Applications with high load or long-lived streaming RPCs might see
 * performance issues because of this queueing. There are two possible solutions:
 *
 * Create a separate channel for each area of high load in the application.
 *
 * Use a pool of gRPC channels to distribute RPCs over multiple connections (channels must have different channel args to prevent re-use
 * so define a use-specific channel arg such as channel number).
 *
 * Side note: The gRPC team has plans to add a feature to fix these performance issues (see grpc/grpc#21386 for more info), so any
 * solution involving creating multiple channels is a temporary workaround that should eventually not be needed.
 *
 *
 */
@Slf4j
public class RpcClientFactory {

    private static SerialEnum globalSerialEnum = SerialEnum.JSON;

    private final ManagedChannel channel;

    private final String       serverName;


    private CacheManager cacheManager;

    private SerialEnum defaultSerial;


    static {
        log.debug("static client GraalvmBuild.forNative.....");
        //io.grpc.ManagedChannelProvider$ProviderNotFoundException: No functional server found. Try adding a dependency on the grpc-netty or grpc-netty-shaded artifact quarkus graal native
        //https://github.com/quarkusio/quarkus/blob/main/extensions/grpc/runtime/src/main/java/io/quarkus/grpc/spi/GrpcBuilderProvider.java
        GraalvmBuild.forNative();
    }

    public RpcClientFactory(String serverName, ManagedChannel channel, CacheManager cacheManager) {
        this.channel = channel;
        this.cacheManager = cacheManager;
        this.serverName = serverName;
    }

    public RpcClientFactory(String serverName, ManagedChannel channel) {
        this(serverName, channel, null);
    }

    public static void setGlobalSerialEnum(SerialEnum defaultSerialEnum) {
        if (defaultSerialEnum != null) {
            RpcClientFactory.globalSerialEnum = defaultSerialEnum;
        }
    }

    public void setCacheManager(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        log.info("{} use CacheManager {}",serverName,cacheManager);
    }

    public void setDefaultSerial(SerialEnum defaultSerial) {
        this.defaultSerial = defaultSerial;
    }

    public SerialEnum getDefaultSerial() {
        if(null == defaultSerial){
            defaultSerial = globalSerialEnum;
        }
        return defaultSerial;
    }


    public void setDefaultCacheManager(Object cacheManager) {
        if(cacheManager instanceof CacheManager){
            setCacheManager((CacheManager) cacheManager);
        }else {
            setCacheManager(new SimpleLRUCache());
        }
    }


    public void close(){
        if(null!=channel){
            log.info("**** shutdown channel...");
            channel.shutdownNow();
        }
    }

    public <T> T get(Class<T> typeClass) {
        return get(typeClass, getDefaultSerial());
    }

    public <T> T get(Class<T> typeClass, SerialEnum serialEnum) {
        return get(typeClass, serialEnum, null);
    }
	public <T> T get(Class<T> typeClass, List<ClientFilter> filterList) {
		return get(typeClass, getDefaultSerial(), filterList);
	}
    public <T> T get(Class<T> typeClass, SerialEnum serialEnum, List<ClientFilter> filterList) {
        InvocationHandler handler = new MethodCallProxyHandler<>(serverName, channel, typeClass
                , filterList, cacheManager, serialEnum);
        T proxy = (T) Proxy.newProxyInstance(typeClass.getClassLoader(), new Class[] {typeClass}, handler);
        return proxy;
    }

}
