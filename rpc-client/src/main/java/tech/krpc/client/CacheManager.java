package tech.krpc.client;

import java.nio.charset.StandardCharsets;

import tech.krpc.annotation.Doc;
import tech.krpc.common.MethodStub;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;

/**
 * 2020-08-25 15:27
 *
 * @author Martin.C
 */
@Doc("客户端缓存")
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
            // O10 (HARDEN-B2): getBs() hands back OutputProto's internal array by reference.
            // Clone so a caller mutating the returned byte[] can't poison the cached entry.
            var bs = message.getBs();
            value = bs == null ? null : bs.clone();
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
            // O10 (HARDEN-B2): clone the cached array before handing it out, so THIS caller's
            // later mutation can't corrupt the shared cache value seen by other threads/calls.
            bd.setBs(bs.clone());
        }
        return bd.build();
    }


    default String cacheKey(MethodStub stub,
                    InputProto input){

        // C2 (HARDEN-B2): key on the explicit dataCase tristate, never a "utf8 non-empty" heuristic.
        // setUtf8("") has dataCase == UTF8 but an empty payload; the old heuristic folded it onto the
        // NOT_SET ("n:") key, so an empty-string arg collided with a no-arg call. Branch on dataCase
        // so UTF8 / BS / DATA_NOT_SET never share a key and byte[] payloads never collapse together.
        String paramKey = switch (input.getDataCase()) {
            case UTF8 -> {
                var json = input.getUtf8();
                yield "u:" + (json.length() > KEY_MAX_SIZE_UNDIGEST
                        ? json.substring(0, 32) + "---" + SimpleMD5.md5(json.getBytes(StandardCharsets.UTF_8))
                        : json);
            }
            case BS -> "b:" + SimpleMD5.md5(input.getBs());
            case DATA_NOT_SET -> "n:";
        };
        return stub.methodDescriptor.getFullMethodName()+":"+paramKey;
    }

    /// must > 0
    default  int expireSeconds(){
        return  DEFAULT_EXPIRE_SECONDS;
    }

}
