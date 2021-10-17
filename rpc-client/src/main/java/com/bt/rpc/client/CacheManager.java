package com.bt.rpc.client;

import java.nio.charset.StandardCharsets;

import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
//import com.bt.rpc.internal.SerEnum;
import com.google.protobuf.ByteString;
import com.bt.rpc.common.MethodStub;

/**
 * 2020-08-25 15:27
 *
 * @author Martin.C
 */
public interface CacheManager {


    int DEFAULT_EXPIRE_SECONDS = 60;

    int KEY_MAX_SIZE_UNDIGEST = 64;


    String get(String cacheKey);

    void set(String cacheKey, String bytesStr, int expireSeconds);



    default void set(MethodStub stub, String cacheKey, OutputProto message){
        String value;
        if(stub.returnType != byte[].class){
            value = message.getJson();
        }else {
            value = message.getBs().toStringUtf8();
        }
        set(cacheKey,value,stub.getExpireSeconds());
    }


    default OutputProto get(MethodStub stub, String cacheKey){
        var bs = get(cacheKey);
        if(null == bs){
            return  null;
        }
        OutputProto.Builder bd = OutputProto.newBuilder();
        if(stub.returnType != byte[].class){
            bd.setJson(bs);
        }else{
            bd.setBs(ByteString.copyFromUtf8(bs));
        }
        return bd.build();
    }


    default String cacheKey(MethodStub stub,
                    InputProto input){

        var json = input.getJson();
        String paramKey  = json;
        if(json.length()>KEY_MAX_SIZE_UNDIGEST){
            paramKey = json.substring(0,32)+"---"+ SimpleMD5.md5(json.getBytes(StandardCharsets.UTF_8));
        }
        return stub.methodDescriptor.getFullMethodName()+":"+paramKey;
    }

    /// must > 0
    default  int expireSeconds(){
        return  DEFAULT_EXPIRE_SECONDS;
    }

}
