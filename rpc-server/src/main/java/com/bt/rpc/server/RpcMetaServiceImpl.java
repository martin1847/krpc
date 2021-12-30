package com.bt.rpc.server;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
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

        var dtos = new HashMap<String, Dto>();

        List<Api> apis = new ArrayList<>();
        methods.stream()
                .collect(Collectors.groupingBy(RpcMetaMethod::getServieName))
                .forEach((k, v) -> {
                    List<Method> apiMethods = new ArrayList<>();
                    for (RpcMetaMethod m : v) {
                        apiMethods.add(new Method(m.getName(),
                                getOrAdd(dtos, m.getArg(),true,"req",null),
                                getOrAdd(dtos, m.getRes(),false,"",null),
                               Stream.of(m.getAnnotations()).map(RpcMetaServiceImpl::toAnno).collect(Collectors.toList())
                        ));
                    }

                    var api = new Api(k, apiMethods, v.get(0).getDescription());
                    apis.add(api);

                });

        var serviceMeta = new ApiMeta(ServerContext.applicationName(),apis, new ArrayList<>(dtos.values()));
        this.result = RpcResult.ok(serviceMeta);

    }

    static   Dto cls2dto(HashMap<String, Dto> dic, Type type,int generics, boolean input) {

        if(null == type){
            return null;
        }

        if(type instanceof Class ){
            var fClz = ((Class<?>) type);
            var fName = fClz.getSimpleName();
            var dto = dic.get(fName);
            if(null!=dto){
                if(input){
                    dto.setInput(input);
                }
                return dto;
            }

            // skip Array , use list
            if(!fClz.isPrimitive()){
                if(fClz.isEnum()){
                    fName = "String";
                }
                dto = new Dto(fName,generics,input);
                if(!fClz.getName().startsWith("java.")){
                    dto.setFields(clsFields(dic,fClz,input));
                }
                dic.put(fName,dto);
            }
            return dto;
        }

        var pt = (ParameterizedType)type;

        for (var t : pt.getActualTypeArguments()){
            cls2dto(dic,t,0,input);
        }
        return cls2dto(dic,pt.getRawType(),pt.getActualTypeArguments().length,input);
    }

    static List<Property> clsFields(HashMap<String, Dto> dic,Class type,boolean input){
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
        }
        return fields.stream().filter(f-> ! Modifier.isStatic(f.getModifiers()))
                    .map(f ->
                            getOrAdd(dic, f.getType(),input ,f.getName(),f.getDeclaredAnnotations())
                    )
                    .collect(Collectors.toList());
    }

    static Property getOrAdd(HashMap<String, Dto> dic, Type t, boolean input,String name,Annotation[] annotations) {


        Dto  rawDto;
        List<Dto> generics = null;
        if(t instanceof ParameterizedType){
            var genTypes = ((ParameterizedType) t).getActualTypeArguments();
            generics = new ArrayList<>();
            for (var genType : genTypes){
                generics.add(cls2dto(dic,genType,0,input));
            }
            rawDto = cls2dto(dic,t,genTypes.length,input);
        }else{
            rawDto = cls2dto(dic,t,0,input);
        }

       return   new Property(name
                , rawDto,generics
                , annotations ==null? null: Stream.of(annotations)
                .map(RpcMetaServiceImpl::toAnno).collect(Collectors.toList())
        );
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
