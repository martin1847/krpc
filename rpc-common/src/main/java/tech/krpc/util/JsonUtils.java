/**
 * Alipay.com Inc.
 * Copyright (c) 2004-2021 All Rights Reserved.
 */
package tech.krpc.util;

import java.lang.reflect.Type;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

/**
 *
 * @author martin.cong
 * @version JsonUtils: JsonUtils.java, v 0.1 2021年10月17日 00:06 young Exp $
 */
public abstract class JsonUtils {
    //private static final Map<ParameterizedType, JavaType> TYPES_MAP = new ConcurrentHashMap<>();

    static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        //MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    @SneakyThrows
    public static String stringify(Object obj) {
        //try {
            return MAPPER.writeValueAsString(obj);
        //} catch (JsonProcessingException e) {
        //    throw new RuntimeException(e);
        //}
    }

    /**
     *  通用类型转换
     *
     *  比如要转换为List<Map<String, String>>
     *
     *  TypeReference<List<Map<String, String>>> MAP_TYPE_REFERENCE = new TypeReference<>() {};
     *  MAP_TYPE_REFERENCE.getType()
     *
     */
    @SneakyThrows(JsonProcessingException.class)
    public static <T> T parse(String json, Type type) {
        return (T)MAPPER.readValue(json,MAPPER.constructType(type));
        //if (type instanceof Class) {
        //    return parse(json, (Class<T>) type);
        //}
        //return parse(json, (ParameterizedType) type);
    }

    /**
     * 泛型使用
     */
    @SneakyThrows(JsonProcessingException.class)
    public static <T> T parse(String json, Class<T> type) {
        return (T) MAPPER.readValue(json,type);
    }
    //}
    //
    ////@Deprecated
    ////public static <T> T parse(String json, TypeReference<T> type) {
    ////    //var typeFactory = MAPPER.getTypeFactory();
    ////    //var listType = typeFactory.constructCollectionType(List.class, type));
    ////    try {
    ////        return MAPPER.readValue(json, type);
    ////    } catch (JsonProcessingException e) {
    ////        throw new RuntimeException(e);
    ////    }
    ////}
    //
    //public static <T> T parse(String json, ParameterizedType type) {
    //    //if (null == json || json.isEmpty()) {
    //    //    return null;
    //    //}
    //    JavaType genc = TYPES_MAP.computeIfAbsent(type, t -> {
    //        var parameterClasses = Stream.of(type.getActualTypeArguments())
    //                .map(it -> (Class) it)
    //                .toArray(Class[]::new);
    //        //            System.out.println( type.getRawType() +" < " + Arrays.toString(parameterClasses));
    //
    //        return MAPPER.getTypeFactory().constructParametricType(
    //                (Class) type.getRawType(), parameterClasses
    //        );
    //    });
    //
    //    try {
    //        return (T) MAPPER.readValue(json, genc);
    //    } catch (JsonProcessingException e) {
    //        throw new RuntimeException(e);
    //    }
    //}

}