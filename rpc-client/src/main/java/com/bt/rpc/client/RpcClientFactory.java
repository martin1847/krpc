package com.bt.rpc.client;

import com.bt.rpc.internal.SerialEnum;
import com.bt.rpc.serial.Serial;
import io.grpc.ManagedChannel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

public class RpcClientFactory {
	private final ManagedChannel channel;

	private final CacheManager cacheManager;

	private final SerialEnum serialEnum;

	public RpcClientFactory(ManagedChannel channel, CacheManager cacheManager) {
		this(channel,cacheManager,SerialEnum.JSON);
	}

	public RpcClientFactory(ManagedChannel channel, CacheManager cacheManager, SerialEnum serialEnum) {
		this.channel = channel;
		this.cacheManager = cacheManager;
		this.serialEnum = serialEnum;
	}

	public RpcClientFactory(ManagedChannel channel) {
		this(channel,null);
	}
	
	
	public  <T> T get(Class<T> typeClass) {
		return get(typeClass,null);
	}

	public  <T> T get(Class<T> typeClass, List<ClientFilter> filterList) {
		InvocationHandler handler = new MethodCallProxyHandler<>(channel, typeClass
					,filterList,cacheManager , serialEnum);
		T proxy = (T) Proxy.newProxyInstance(typeClass.getClassLoader(), new Class[] {typeClass}, handler);
		return proxy;
	}

}
