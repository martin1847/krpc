package tech.krpc.server;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
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

import tech.krpc.annotation.Doc;
import tech.krpc.common.RpcMetaService;
import tech.krpc.common.meta.Anno;
import tech.krpc.common.meta.Api;
import tech.krpc.common.meta.ApiMeta;
import tech.krpc.common.meta.Dto;
import tech.krpc.common.meta.Method;
import tech.krpc.common.meta.Property;
import tech.krpc.common.meta.PropertyType;
import tech.krpc.internal.SerialEnum;
import tech.krpc.model.RpcResult;
import tech.krpc.serial.Serial;
import tech.krpc.server.RpcServerBuilder.RpcMetaMethod;
import lombok.extern.slf4j.Slf4j;

/**
 * 2020-04-08 11:03
 *
 * @author Martin.C
 */
@Slf4j
class RpcMetaServiceImpl implements RpcMetaService {

    private RpcResult<ApiMeta> result;

    @Override
    public RpcResult<ApiMeta> listApis() {
        return result;
    }


    @Override
    public RpcResult<List<String>> serials() {
        return RpcResult.ok(Stream.of(Serial.Instance.supported()).map(SerialEnum::name).collect(Collectors.toList()));
    }

    public void init(ApiMeta meta) {
        this.result = RpcResult.ok(meta);
    }

    static ApiMeta buildApiMeta(List<RpcMetaMethod> methods) {
        var dtos = new HashMap<String, Dto>();

        List<Api> apis = new ArrayList<>();
        methods.stream()
                .collect(Collectors.groupingBy(RpcMetaMethod::getServieName))
                .forEach((k, v) -> {
                    List<Method> apiMethods = new ArrayList<>();
                    for (RpcMetaMethod m : v) {
                        apiMethods.add(new Method(m.getName(),
                                getOrAdd(dtos, m.getArg(), true),
                                getOrAdd(dtos, m.getRes(), false),
                                toAnno(m.getAnnotations())
                        ));
                    }

                    var api = new Api(k, apiMethods, v.get(0).getDescription());
                    apis.add(api);

                });

        return new ApiMeta(ServerContext.applicationName(), apis, new ArrayList<>(dtos.values()));
    }

    static Dto cls2dto(HashMap<String, Dto> dic, Class fClz, int generics, boolean input) {
        var fName = fClz.getSimpleName();
        var dto = dic.get(fName);
        if (null != dto) {
            if (input) {
                dto.setInput(input);
            }
            return dto;
        }

        String doc = fClz.isAnnotationPresent(Doc.class) ? ((Doc) fClz.getAnnotation(Doc.class)).value() : null;

        if (fClz.isEnum()) {
            //fName = "String";

            return dic.computeIfAbsent(fName, k -> {
                var d = new Dto(k, generics, input, doc);
                var fields = Arrays.stream(fClz.getEnumConstants()).map(it -> new Property(it.toString(), null, null))
                        .collect(Collectors.toList());
                d.setFields(fields);
                return d;
            });
        }
        dto = dic.computeIfAbsent(fName, k -> new Dto(k, generics, input, doc));

        // skip Array , use list
        if (!fClz.isPrimitive() && !fClz.getName().startsWith("java.")) {
            dto.setFields(clsFields(dic, fClz, input));
        }
        return dto;
    }

    static List<Property> clsFields(HashMap<String, Dto> dic, Class type, boolean input) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
        }
        return fields.stream().filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(f -> {
                    var pType = getOrAdd(dic, f.getGenericType(), input);
                    var name = f.getName();
                    var annos = toAnno(f.getDeclaredAnnotations());
                    return new Property(name, pType, annos);
                })
                .collect(Collectors.toList());
    }

    static PropertyType getOrAdd(HashMap<String, Dto> dic, Type t, boolean input) {

        if (null == t) {
            return null;
        }

        //List<T> field;
        if (t instanceof ParameterizedType) {
            var genTypes = ((ParameterizedType) t).getActualTypeArguments();
            List<PropertyType> generics = new ArrayList<>();
            for (var genType : genTypes) {
                generics.add(getOrAdd(dic, genType, input));
            }

            Dto rawDto = cls2dto(dic, (Class) ((ParameterizedType) t).getRawType(), generics.size(), input);
            //System.out.println("Get TypeDto : "+ rawDto.getTypeName());
            //System.out.println("Get TypeDtoGens : "+ generics.stream()
            //        .map(it->it.getRawType().getTypeName()).collect(Collectors.toList()));

            return new PropertyType(rawDto, generics);
        } else if (t instanceof GenericArrayType) {
            Dto rawDto = dic.computeIfAbsent("List", k -> new Dto(k, 1, input,null));
            var compType = ((GenericArrayType) t).getGenericComponentType();
            String typeName = "T";
            if (compType instanceof TypeVariable) {
                typeName = ((TypeVariable<?>) compType).getName();
            }
            var genericType = new PropertyType(dic.computeIfAbsent(typeName, k -> new Dto(k, 0, input, null,true)));
            return new PropertyType(rawDto, Collections.singletonList(genericType));
            // T field;
        } else if (t instanceof TypeVariable) {
            String typeName = ((TypeVariable<?>) t).getName();
            return new PropertyType(dic.computeIfAbsent(typeName, k -> new Dto(k, 0, input, null,true)));
        } else {
            Dto rawDto = cls2dto(dic, (Class) t, 0, input);
            return new PropertyType(rawDto);
        }
    }

    public static List<Anno> toAnno(Annotation[] annotations) {
        List<Anno> annos = new ArrayList<>();
        for (var annotation : annotations) {
            Map<String, Object> params = new HashMap<>();
            for (var param : annotation.annotationType().getMethods()) {
                if (param.getDeclaringClass() == annotation.annotationType()) { //this filters out built-in methods, like hashCode etc
                    try {
                        var val = param.invoke(annotation);
                        if (val instanceof Collection && ((Collection<?>) val).isEmpty()) {
                            continue;
                        }
                        if (val.getClass().isArray() && Array.getLength(val) == 0) {
                            continue;
                        }
                        if (val instanceof String && ((String) val).isEmpty()) {
                            continue;
                        }

                        // skip default message
                        if ("message".equals(param.getName()) && String.valueOf(val).startsWith("{jakarta.validation")) {
                            continue;
                        }

                        params.put(param.getName(), val);
                    } catch (Exception e) {
                        //ignore
                        log.warn("ignore  param  of  " + annotation,e);
                    }
                }
            }
            annos.add(new Anno(annotation.annotationType().getSimpleName(), params));
        }
        return annos.isEmpty() ? null : annos;
    }

}
