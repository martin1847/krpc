package com.bt.rpc.util;

//import com.alibaba.fastjson.JSON;
//import com.alibaba.fastjson.serializer.ByteBufferCodec;
//import com.alibaba.fastjson.serializer.SerializeConfig;
//import com.alibaba.fastjson.serializer.SerializerFeature;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public abstract class SerializationUtils {


//    static {
//        SerializeConfig.globalInstance.put(ByteBuffer.wrap(new byte[]{}).getClass(), ByteBufferCodec.instance);
//        JSON.DEFAULT_GENERATE_FEATURE |= SerializerFeature.DisableCircularReferenceDetect.getMask();
//
//    }

    static final ObjectMapper JSON = new ObjectMapper();
    static {
        JSON.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
    public static <T> ByteString serialize(T obj) {
        if (null == obj) {
            return null;
        }
        return ByteString.copyFromUtf8(toJson(obj));
    }
//
    public static String toJson(Object obj){
        try {
            return   JSON.writeValueAsString(obj);

//            System.out.println( obj +" to -> " + tr);
//            return tr;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }


    public static <T> T deserialize(ByteString str, Type  type) {
        if ( type instanceof  Class ) {
            return deserialize(str, (Class< T>) type);
        }
        return deserialize(str, (ParameterizedType) type);
    }

    public static <T> T deserialize(ByteString str, Class<T> type) {
        if (null == str || str.isEmpty()) {
            return null;
        }
        try {
            return (T) JSON.readValue(str.toStringUtf8(), type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

        private static final Map<ParameterizedType, JavaType> TYPES_MAP = new ConcurrentHashMap<>();

    public static <T> T deserialize(ByteString str, ParameterizedType type) {
        if (null == str || str.isEmpty()) {
            return null;
        }
        JavaType genc = TYPES_MAP.computeIfAbsent(type,t->{

             var parameterClasses =  Stream.of(type.getActualTypeArguments())
                    .map(it->(Class)it)
                    .toArray(Class[]::new);
//            System.out.println( type.getRawType() +" < " + Arrays.toString(parameterClasses));

            return JSON.getTypeFactory().constructParametricType(
                    (Class) type.getRawType(),parameterClasses
            );
        });

        try {
            return (T) JSON.readValue(str.toStringUtf8(), genc);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}