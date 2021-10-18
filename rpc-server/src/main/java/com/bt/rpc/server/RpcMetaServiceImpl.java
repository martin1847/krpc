package com.bt.rpc.server;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.bt.rpc.common.RpcMetaService;
import com.bt.rpc.common.meta.Api;
import com.bt.rpc.common.meta.ApiMeta;
import com.bt.rpc.common.meta.Dto;
import com.bt.rpc.common.meta.Method;
import com.bt.rpc.common.meta.Property;
import com.bt.rpc.model.RpcResult;
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

    public void init(List<RpcMetaMethod> methods) {

        var dtos = new HashMap<Type, Dto>();

        List<Api> apis = new ArrayList<>();
        methods.stream()
                .collect(Collectors.groupingBy(RpcMetaMethod::getServieName))
                .forEach((k, v) -> {
                    List<Method> apiMethods = new ArrayList<>();
                    for (var m : v) {
                        apiMethods.add(new Method(m.getName(), getOrAdd(dtos, m.getArg()),
                                getOrAdd(dtos, m.getRes()), m.getAnnotations()));
                    }

                    var api = new Api(k, apiMethods, v.get(0).getDescription());
                    apis.add(api);

                });

        var serviceMeta = new ApiMeta(apis, new ArrayList<>(dtos.values()));
        this.result = RpcResult.ok(serviceMeta);

        //System.out.println(JSON.toJSONString(serviceMeta));
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
                    .map(f ->
                            new Property(f.getName()
                                    , getOrAdd(dic, f.getType())
                                    , Stream.of(f.getDeclaredAnnotations())
                                    .map(Annotation::toString).collect(Collectors.toList())
                            )
                    )
                    .collect(Collectors.toList());
            dd.setFields(fields);
        }
        dic.put(t, dd);
        return dd;

    }

//    static Dto type2dto(Type tp){
//        StringBuilder gen = null;
//        var t = tp;
//        if (t instanceof ParameterizedType) {
//            tp = ((ParameterizedType) t).getRawType();
//            var genType = ((ParameterizedType) t).getActualTypeArguments();
//            if (genType != null && genType.length > 0) {
//                gen = new StringBuilder(30).append('<');
//                for (var genT : genType) {
//                    gen.append(((Class) genT).getSimpleName()).append(',');
//                    getOrAdd(dic, genT);
//                }
//                gen.setCharAt(gen.length() - 1, '>');
//            }
//        }
//        var clz = (Class) tp;
//        var name = gen == null ? clz.getSimpleName() : clz.getSimpleName() + gen.toString();
//        var ns = clz.getPackageName();
//        var dd = new Dto();
//        dd.setName(name);
//        if (ns == null || !ns.startsWith("java")) {
//
//
//
//            var fields = Stream.of(clz.getDeclaredFields())
//                    .map(f->
//                            new Property(f.getName()
//                                    ,getOrAdd(dic, f.getType())
//                                    , Stream.of(f.getDeclaredAnnotations())
//                                    .map(Annotation::toString).collect(Collectors.toList())
//                            )
//                    )
//                    .collect(Collectors.toList());
//            dd.setFields(fields);
//        }
//        return dd;
//    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RpcMetaMethod {

        String servieName;
        String name;
        Class arg;
        Type res;

        String description;

        List<String> annotations;
    }


}
