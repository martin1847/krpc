package com.bt.rpc.util;

//import com.alibaba.fastjson.JSON;
//import com.alibaba.fastjson.serializer.ByteBufferCodec;
//import com.alibaba.fastjson.serializer.SerializeConfig;
//import com.alibaba.fastjson.serializer.SerializerFeature;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import com.google.protobuf.ByteString;

public abstract class SerializationUtils {


//    static {
//        SerializeConfig.globalInstance.put(ByteBuffer.wrap(new byte[]{}).getClass(), ByteBufferCodec.instance);
//        JSON.DEFAULT_GENERATE_FEATURE |= SerializerFeature.DisableCircularReferenceDetect.getMask();
//
//    }


    public static <T> ByteString serialize(T obj) {
        if (null == obj) {
            return null;
        }
        return ByteString.copyFromUtf8(JsonUtils.stringify(obj));
    }
//


    public static <T> T deserialize(ByteString str, Type  type) {
        if ( type instanceof  Class ) {
            return deserialize(str, (Class< T>) type);
        }
        return deserialize(str, (ParameterizedType) type);
    }

    public static <T> T deserialize(ByteString str, Class<T> type) {
        if (null != str) {
            return JsonUtils.parse(str.toStringUtf8(),type);
        }
        return null;
    }


    public static <T> T deserialize(ByteString str, ParameterizedType type) {
        if (null != str) {
            return JsonUtils.parse(str.toStringUtf8(),type);
        }
        return  null;

    }
}