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


    byte[] get(String cacheKey);

    void set(String cacheKey, byte[] bytesStr, int expireSeconds);



    default void set(MethodStub stub, String cacheKey, OutputProto message){
        byte[] value;
        if(stub.returnType != byte[].class){
            value = message.getUtf8().getBytes(StandardCharsets.UTF_8);
        }else {
            value = message.getBs().toByteArray();
        }
        set(cacheKey,value,stub.getExpireSeconds());
    }


    // TODO check with different serType
    // change to cache DTO / RpcResult
    default OutputProto get(MethodStub stub, String cacheKey){
        var bs = get(cacheKey);
        if(null == bs){
            return  null;
        }
        OutputProto.Builder bd = OutputProto.newBuilder();
        if(stub.returnType != byte[].class){
            bd.setUtf8(new String(bs,StandardCharsets.UTF_8));
        }else{
            bd.setBs(ByteString.copyFrom(bs));
        }
        return bd.build();
    }


    default String cacheKey(MethodStub stub,
                    InputProto input){

        var json = input.getUtf8();
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
