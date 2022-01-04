package com.bt.rpc.server;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
import com.bt.rpc.common.meta.PropertyType;
import com.bt.rpc.internal.SerialEnum;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.serial.Serial;
import com.bt.rpc.server.RpcServerBuilder.RpcMetaMethod;
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

    public void init(ApiMeta meta) {
        this.result = RpcResult.ok(meta);
    }

    static ApiMeta buildApiMeta(List<RpcMetaMethod> methods){
        var dtos = new HashMap<String, Dto>();

        List<Api> apis = new ArrayList<>();
        methods.stream()
                .collect(Collectors.groupingBy(RpcMetaMethod::getServieName))
                .forEach((k, v) -> {
                    List<Method> apiMethods = new ArrayList<>();
                    for (RpcMetaMethod m : v) {
                        apiMethods.add(new Method(m.getName(),
                                getOrAdd(dtos, m.getArg(),true),
                                getOrAdd(dtos, m.getRes(),false),
                                toAnno(m.getAnnotations())
                        ));
                    }

                    var api = new Api(k, apiMethods, v.get(0).getDescription());
                    apis.add(api);

                });

        return new ApiMeta(ServerContext.applicationName(),apis, new ArrayList<>(dtos.values()));
    }

    static   Dto cls2dto(HashMap<String, Dto> dic, Class fClz,int generics, boolean input) {
            var fName = fClz.getSimpleName();
            var dto = dic.get(fName);
            if(null!=dto){
                if(input){
                    dto.setInput(input);
                }
                return dto;
            }
            if(fClz.isEnum()){
                fName = "String";
            }
            dto = dic.computeIfAbsent(fName,k->new Dto(k,generics,input));

            // skip Array , use list
            if(!fClz.isPrimitive() && !fClz.getName().startsWith("java.")){
                dto.setFields(clsFields(dic,fClz,input));
            }
            return dto;
    }

    static List<Property> clsFields(HashMap<String, Dto> dic,Class type,boolean input){
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
        }
        return fields.stream().filter(f-> ! Modifier.isStatic(f.getModifiers()))
                    .map(f ->{
                           var pType =   getOrAdd(dic, f.getGenericType(),input);
                           var name = f.getName();
                           var annos = toAnno(f.getDeclaredAnnotations());
                           return new Property(name,pType,annos);
                    })
                    .collect(Collectors.toList());
    }

    static PropertyType getOrAdd(HashMap<String, Dto> dic, Type t, boolean input) {

        if(null == t){
            return null;
        }

        //List<T> field;
        if(t instanceof ParameterizedType){
            var genTypes = ((ParameterizedType) t).getActualTypeArguments();
            List<PropertyType> generics = new ArrayList<>();
            for (var genType : genTypes){
                generics.add(getOrAdd(dic,genType,input));
            }

            Dto rawDto = cls2dto(dic, (Class) ((ParameterizedType) t).getRawType(),generics.size(),input);
            //System.out.println("Get TypeDto : "+ rawDto.getTypeName());
            //System.out.println("Get TypeDtoGens : "+ generics.stream()
            //        .map(it->it.getRawType().getTypeName()).collect(Collectors.toList()));

            return new PropertyType(rawDto,generics);
        }else if(t instanceof GenericArrayType){
            Dto rawDto = dic.computeIfAbsent("List",k-> new Dto(k, 1,input));
            var compType =  ((GenericArrayType) t).getGenericComponentType();
            String typeName = "T";
            if(compType instanceof  TypeVariable){
                typeName =  ((TypeVariable<?>) compType).getName();
            }
            var genericType = new PropertyType(dic.computeIfAbsent(typeName,k-> new Dto(k,0,input,true)));
            return new PropertyType(rawDto,Collections.singletonList(genericType));
            // T field;
        }else if(t instanceof TypeVariable){
            String typeName = ((TypeVariable<?>) t).getName();
            return new PropertyType(dic.computeIfAbsent(typeName,k-> new Dto(k,0,input,true)));
        }else {
            Dto rawDto = cls2dto(dic, (Class) t,0,input);
            return new PropertyType(rawDto);
        }
    }



    public static List<Anno> toAnno(Annotation[] annotations){
        List<Anno> annos = new ArrayList<>();
        for(var annotation:annotations) {
            Map<String, Object> params = new HashMap<>();
            for (var param : annotation.annotationType().getMethods()) {
                if (param.getDeclaringClass() == annotation.annotationType()) { //this filters out built-in methods, like hashCode etc
                    try {
                        var val = param.invoke(annotation);
                        if(val instanceof Collection && ((Collection<?>) val).isEmpty()){
                            continue;
                        }
                        if(val.getClass().isArray() && Array.getLength(val) ==0){
                            continue;
                        }
                        if(val instanceof String && ((String) val).isEmpty()){
                            continue;
                        }

                        // skip default message
                        if("message".equals(param.getName()) && String.valueOf(val).startsWith("{javax.validation")){
                            continue;
                        }

                        params.put(param.getName(), val);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            annos.add(new Anno(annotation.annotationType().getSimpleName(), params));
        }
        return annos.isEmpty()? null: annos;
    }


}
