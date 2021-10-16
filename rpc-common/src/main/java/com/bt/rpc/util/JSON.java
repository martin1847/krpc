/**
 * Alipay.com Inc.
 * Copyright (c) 2004-2021 All Rights Reserved.
 */
package com.bt.rpc.util;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;

/**
 *
 * @author martin.cong
 * @version JsonUtils: JsonUtils.java, v 0.1 2021年10月17日 00:06 young Exp $
 */
public abstract class JSON {
    private static final Map<ParameterizedType, JavaType> TYPES_MAP = new ConcurrentHashMap<>();


    static final ObjectMapper MAPPER = new ObjectMapper();
    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
    public static String stringify(Object obj){
        try {
            return   MAPPER.writeValueAsString(obj);

            //            System.out.println( obj +" to -> " + tr);
            //            return tr;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T parse(String json, Type type) {
        if ( type instanceof  Class ) {
            return parse(json, (Class< T>) type);
        }
        return parse(json, (ParameterizedType) type);
    }

    public static  <T> T  parse(String json, Class<T> type){
        try {
            return (T) MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static  <T> T  parse(String json, ParameterizedType type) {
        JavaType genc = TYPES_MAP.computeIfAbsent(type,t->{
            var parameterClasses =  Stream.of(type.getActualTypeArguments())
                    .map(it->(Class)it)
                    .toArray(Class[]::new);
            //            System.out.println( type.getRawType() +" < " + Arrays.toString(parameterClasses));

            return MAPPER.getTypeFactory().constructParametricType(
                    (Class) type.getRawType(),parameterClasses
            );
        });

        try {
            return (T) MAPPER.readValue(json, genc);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

}