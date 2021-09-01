package com.bt.rpc.client;

import com.bt.rpc.internal.InputMessage;
import com.bt.rpc.internal.OutputMessage;
import com.bt.rpc.internal.SerEnum;
import com.google.protobuf.ByteString;
import com.bt.rpc.util.MethodStub;

/**
 * 2020-08-25 15:27
 *
 * @author Martin.C
 */
public interface CacheManager {


    int DEFAULT_EXPIRE_SECONDS = 60;

    int KEY_MAX_SIZE_UNDIGEST = 32;


    String get(String cacheKey);

    void set(String cacheKey, String bytesStr, int expireSeconds);



    default void set(MethodStub stub, String cacheKey, OutputMessage message){
        String value;
        if(stub.returnType == String.class){
            value = message.getS();
        }else {
            value = message.getB().toStringUtf8();
        }
        set(cacheKey,value,stub.getExpireSeconds());
    }


    default OutputMessage get(MethodStub stub,String cacheKey){
        var bs = get(cacheKey);
        if(null == bs){
            return  null;
        }
        OutputMessage.Builder bd = OutputMessage.newBuilder();
        bd.setSe(SerEnum.JSON);
        if(stub.returnType == String.class){
            bd.setS(bs);
        }else{
            bd.setB(ByteString.copyFromUtf8(bs));
        }
        return bd.build();
    }


    default String cacheKey(MethodStub stub,
                    InputMessage input){

        var bytes = input.getB();
        String paramKey ;
        if(bytes.size()>KEY_MAX_SIZE_UNDIGEST){
            paramKey = SimpleMD5.md5(bytes.toByteArray());
        }else{
            paramKey = bytes.toStringUtf8();
        }
        return stub.methodDescriptor.getFullMethodName()+":"+paramKey;
    }

    /// must > 0
    default  int expireSeconds(){
        return  DEFAULT_EXPIRE_SECONDS;
    }

}
