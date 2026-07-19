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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
                    checkFieldContract(f);
                    var pType = getOrAdd(dic, f.getGenericType(), input);
                    var name = f.getName();
                    var annos = toAnno(f.getDeclaredAnnotations());
                    return new Property(name, pType, annos);
                })
                .collect(Collectors.toList());
    }

    // META-ARRAY-001 (SPEC §4 "Collection fields"): the contract meta does not model array
    // component types, so a Java array field yields generation that references an undeclared
    // type (silent broken output). Policy: use List<T> for sequences. Arrays are UNSUPPORTED
    // except the ONE exemption below; Map<K,V> is NOT RECOMMENDED (a WARN, not an error).
    private static void checkFieldContract(Field f) {
        // Inspect the GENERIC type: f.getType() erases T[] to Object[], which would wrongly
        // reject a type-variable array that getOrAdd models as List<T>.
        rejectUnsupportedArray(f.getGenericType(),
                "DTO " + f.getDeclaringClass().getName() + " field '" + f.getName() + "'");
        if (Map.class.isAssignableFrom(f.getType())) {
            warnMapOnce(f.getDeclaringClass().getName(), f.getName());
        }
    }

    // META-ARRAY-001: the single choke point for array policy, shared by the field check and the
    // getOrAdd type paths. Rejects the array shapes the meta scan cannot model — a concrete array
    // that is not a single-dimension primitive array (Zebra[], String[], int[][]), and a generic
    // array with a concrete/parameterized component (List<String>[]). Type-variable arrays (T[])
    // pass through (getOrAdd models them as List<T>); single-dimension primitive arrays are exempt.
    private static void rejectUnsupportedArray(Type t, String context) {
        if (t instanceof Class && ((Class<?>) t).isArray() && !isExemptPrimitiveArray((Class<?>) t)) {
            throw arrayNotSupported(context, ((Class<?>) t).getSimpleName());
        }
        if (t instanceof GenericArrayType
                && !(((GenericArrayType) t).getGenericComponentType() instanceof TypeVariable)) {
            throw arrayNotSupported(context, t.getTypeName());
        }
    }

    // The ONLY exempt array shape: a SINGLE-DIMENSION array of a primitive (byte[], int[], …) —
    // the binary/scalar payload convention. Multi-dimensional arrays (int[][]: componentType is
    // int[], not primitive) and object arrays (Zebra[], String[]) are UNSUPPORTED. `arrayType`
    // MUST already be an array type.
    private static boolean isExemptPrimitiveArray(Class<?> arrayType) {
        return arrayType.getComponentType().isPrimitive();
    }

    private static IllegalStateException arrayNotSupported(String context, String arrayTypeName) {
        return new IllegalStateException(
                "META-ARRAY-001: " + context + " uses unsupported array type " + arrayTypeName
                + "; use List<T> instead. The contract meta does not model array component types — "
                + "object arrays and multi-dimensional arrays are UNSUPPORTED (single-dimension "
                + "primitive arrays like byte[] are the only exemption). See SPEC §4 'Collection fields'.");
    }

    // META-ARRAY-001: a server build scans up to three metas (full/web/mcp), so dedup the Map
    // WARN by declaringClass#field to avoid logging the same field up to 3×.
    private static final Set<String> WARNED_MAP_FIELDS = ConcurrentHashMap.newKeySet();

    private static void warnMapOnce(String declaringClass, String field) {
        if (WARNED_MAP_FIELDS.add(declaringClass + "#" + field)) {
            log.warn("META-ARRAY-001: DTO {} field '{}' uses Map<K,V>; generated client code "
                    + "loses readability — model the shape as an explicit DTO class instead "
                    + "(SPEC §4 'Collection fields', NOT RECOMMENDED).", declaringClass, field);
        }
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
            // META-ARRAY-001: T[] (type-variable component) stays modeled as List<T> for generic
            // DTOs; a generic array with a concrete/parameterized component (List<String>[]) is
            // rejected by the shared array policy.
            rejectUnsupportedArray(t, "contract meta type");
            var compType = ((GenericArrayType) t).getGenericComponentType();
            Dto rawDto = dic.computeIfAbsent("List", k -> new Dto(k, 1, input, null));
            String typeName = ((TypeVariable<?>) compType).getName();
            var genericType = new PropertyType(dic.computeIfAbsent(typeName, k -> new Dto(k, 0, input, null, true)));
            return new PropertyType(rawDto, Collections.singletonList(genericType));
            // T field;
        } else if (t instanceof TypeVariable) {
            String typeName = ((TypeVariable<?>) t).getName();
            return new PropertyType(dic.computeIfAbsent(typeName, k -> new Dto(k, 0, input, null,true)));
        } else {
            Class<?> cls = (Class<?>) t;
            // META-ARRAY-001: backstop for arrays reaching the raw-Class branch via method arg/res
            // or nested generics (e.g. List<Zebra[]>); direct DTO fields fail earlier (richer
            // context) in checkFieldContract. No cheap declaring context here (findings R2 #5) —
            // report the array type + remedy. Single-dimension primitive arrays are exempt.
            rejectUnsupportedArray(cls, "contract meta type");
            Dto rawDto = cls2dto(dic, cls, 0, input);
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
