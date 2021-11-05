package com.bt.rpc.server;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.validation.constraints.Size;

import com.bt.rpc.annotation.Doc;
import com.bt.rpc.common.RpcConstants;
import com.bt.rpc.common.RpcMetaService;
import com.bt.rpc.common.meta.Anno;
import com.bt.rpc.common.meta.Api;
import com.bt.rpc.common.meta.ApiMeta;
import com.bt.rpc.common.meta.Dto;
import com.bt.rpc.common.meta.Method;
import com.bt.rpc.common.meta.Property;
import com.bt.rpc.internal.SerialEnum;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.serial.Serial;
import com.bt.rpc.util.EnvUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 2020-04-08 11:03
 *
 * @author Martin.C
 */
class RpcMetaServiceImpl implements RpcMetaService {

    private RpcResult<ApiMeta> result;

    @Override
    public RpcResult<ApiMeta> listApis() {
        return result;
    }

    @Override
    public RpcResult<String> v() {
        return RpcResult.ok(RpcConstants.CI_BUILD_ID+"-"+ EnvUtils.hostName());
    }

    @Override
    public RpcResult<List<String>> serials() {
        return RpcResult.ok(Stream.of(Serial.Instance.supported()).map(SerialEnum::name).collect(Collectors.toList()));
    }

    public void init(List<RpcMetaMethod> methods) {

        var dtos = new HashMap<Type, Dto>();

        List<Api> apis = new ArrayList<>();
        methods.stream()
                .collect(Collectors.groupingBy(RpcMetaMethod::getServieName))
                .forEach((k, v) -> {
                    List<Method> apiMethods = new ArrayList<>();
                    for (var m : v) {
                        apiMethods.add(new Method(m.getName(), getOrAdd(dtos, m.getArg()),
                                getOrAdd(dtos, m.getRes()),
                               Stream.of(m.getAnnotations()).map(RpcMetaServiceImpl::toAnno).collect(Collectors.toList())
                        ));
                    }

                    var api = new Api(k, apiMethods, v.get(0).getDescription());
                    apis.add(api);

                });

        var serviceMeta = new ApiMeta(ServerContext.applicationName(),apis, new ArrayList<>(dtos.values()));
        this.result = RpcResult.ok(serviceMeta);

    }

    static Dto getOrAdd(HashMap<Type, Dto> dic, Type t) {
        if (null == t) {
            return null;
        }

        var dto = dic.get(t);

        if (dto != null) {
            return dto;
        }
        var tp = t;


        StringBuilder gen = null;
        if (t instanceof ParameterizedType) {
            tp = ((ParameterizedType) t).getRawType();
            var genType = ((ParameterizedType) t).getActualTypeArguments();
            if (genType != null && genType.length > 0) {
                gen = new StringBuilder(30).append('<');
                for (var genT : genType) {
                    gen.append(((Class) genT).getSimpleName()).append(',');
                    getOrAdd(dic, genT);
                }
                gen.setCharAt(gen.length() - 1, '>');
            }
        }
        var clz = (Class) tp;
        var name = gen == null ? clz.getSimpleName() : clz.getSimpleName() + gen.toString();
        var ns = clz.getPackageName();
        var dd = new Dto();
        dd.setName(name);
        if (ns == null || !ns.startsWith("java")) {


            var fields = Stream.of(clz.getDeclaredFields())
                    .filter(f-> ! Modifier.isStatic(f.getModifiers()))
                    .map(f ->
                            new Property(f.getName()
                                    , getOrAdd(dic, f.getType())
                                    , Stream.of(f.getDeclaredAnnotations())
                                    .map(RpcMetaServiceImpl::toAnno).collect(Collectors.toList())
                            )
                    )
                    .collect(Collectors.toList());
            dd.setFields(fields);
        }
        dic.put(t, dd);
        return dd;

    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RpcMetaMethod {

        String servieName;
        String name;
        Type arg;
        Type res;

        String description;

        Annotation[] annotations;
    }

    public static Anno toAnno(Annotation annotation){
        Map<String, Object> params = new HashMap<>();
        for (var param : annotation.annotationType().getMethods()) {
            if (param.getDeclaringClass() == annotation.annotationType()) { //this filters out built-in methods, like hashCode etc
                try {
                    params.put(param.getName(), param.invoke(annotation));
                } catch (IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        }
        return new Anno(annotation.annotationType().getSimpleName(), params);
    }


}
