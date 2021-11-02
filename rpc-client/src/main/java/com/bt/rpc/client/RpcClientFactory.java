package com.bt.rpc.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import com.bt.rpc.internal.SerialEnum;
import io.grpc.ManagedChannel;

public class RpcClientFactory {

    private static SerialEnum defaultSerialEnum = SerialEnum.JSON;

    private final ManagedChannel channel;

    private final CacheManager cacheManager;
    private final String       serverName;

    public RpcClientFactory(String serverName, ManagedChannel channel, CacheManager cacheManager) {
        this.channel = channel;
        this.cacheManager = cacheManager;
        this.serverName = serverName;
    }

    public RpcClientFactory(String serverName, ManagedChannel channel) {
        this(serverName, channel, null);
    }

    public static void setDefaultSerialEnum(SerialEnum defaultSerialEnum) {
        if (defaultSerialEnum != null) {
            RpcClientFactory.defaultSerialEnum = defaultSerialEnum;
        }
    }

    public <T> T get(Class<T> typeClass) {
        return get(typeClass, defaultSerialEnum);
    }

    public <T> T get(Class<T> typeClass, SerialEnum serialEnum) {
        return get(typeClass, serialEnum, null);
    }
	public <T> T get(Class<T> typeClass, List<ClientFilter> filterList) {
		return get(typeClass, defaultSerialEnum, filterList);
	}
    public <T> T get(Class<T> typeClass, SerialEnum serialEnum, List<ClientFilter> filterList) {
        InvocationHandler handler = new MethodCallProxyHandler<>(serverName, channel, typeClass
                , filterList, cacheManager, serialEnum);
        T proxy = (T) Proxy.newProxyInstance(typeClass.getClassLoader(), new Class[] {typeClass}, handler);
        return proxy;
    }

}
