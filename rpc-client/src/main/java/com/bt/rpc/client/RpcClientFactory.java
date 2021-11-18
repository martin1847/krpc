package com.bt.rpc.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import com.bt.rpc.internal.SerialEnum;
import io.grpc.ManagedChannel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcClientFactory {

    private static SerialEnum globalSerialEnum = SerialEnum.JSON;

    private final ManagedChannel channel;

    private final String       serverName;


    private CacheManager cacheManager;

    private SerialEnum defaultSerial;

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
