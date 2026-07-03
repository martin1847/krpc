/**
 * Alipay.com Inc.
 * Copyright (c) 2004-2021 All Rights Reserved.
 */
package tech.krpc.util;

import java.lang.reflect.Type;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author martin.cong
 * @version JsonUtils: JsonUtils.java, v 0.1 2021年10月17日 00:06 young Exp $
 */
@Slf4j
public abstract class JsonUtils {
    //private static final Map<ParameterizedType, JavaType> TYPES_MAP = new ConcurrentHashMap<>();

    static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        var jsr310Exists = "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule";
        try {
            var module = Class.forName(jsr310Exists).getDeclaredConstructor().newInstance();
            MAPPER.registerModule((Module) module);
            log.info("jackson jsr310.JavaTimeModule registered");
        } catch (Exception e) {
            //ignore
        }
        //MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    public static String stringify(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
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
    public static <T> T parse(String json, Type type) {
        try {
            return (T)MAPPER.readValue(json,MAPPER.constructType(type));
        } catch (JsonProcessingException e) {
            // AUD-omp-31: typed + sanitized. Never leak Jackson internals (field/class names, offsets)
            // to a client; the raw exception is kept as the cause for server-side logs only.
            throw new JsonDecodeException("malformed JSON: cannot decode request body", e);
        }
    }

    /**
     * 泛型使用
     */
    public static <T> T parse(String json, Class<T> type) {
        try {
            return (T) MAPPER.readValue(json,type);
        } catch (JsonProcessingException e) {
            // AUD-omp-31: see the Type overload above.
            throw new JsonDecodeException("malformed JSON: cannot decode request body", e);
        }
    }

}