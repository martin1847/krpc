package com.bt.rpc.util;

//import com.alibaba.fastjson.util.ParameterizedTypeImpl;
import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.annotation.UnsafeWeb;
import com.bt.rpc.common.MethodStub;
import com.bt.rpc.common.RpcMetaService;
import com.bt.rpc.model.RpcResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 2020-01-09 15:01
 *
 * @author Martin.C
 */
public abstract class RefUtils {

    //static final boolean TRIM_SERVICE_NAME = "1".equals(System.getenv("TRIM_SERVICE_NAME"));


    static class GenericTypeBySignature{
        private static Field signature = null;

        static {
            try {
                signature = Method.class.getDeclaredField("signature");
                signature.setAccessible(true);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        }


        public static Type findRpcResultGenericType(Method method) {


            var patt = RpcResult.class.getSimpleName() + "<";
            var pattLen = patt.length();


            String sign = null;
            try {
                sign = (String) signature.get(method);
                //System.out.println(sign);
                sign = sign.substring(sign.lastIndexOf(patt) + pattLen, sign.length() - 2);
                //Ljava/util/List<Ljava/lang/Integer;>;
                //Ljava/lang/String;
                //Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;
                //[B

                int subLt = sign.indexOf('<');
                if (subLt < 0) {
                    return signClass(sign);
                } else {
                    var raw = signClass(sign.substring(0, subLt));
                    var types = sign.substring(subLt + 1, sign.length() - 2).split(";");
                    var typeArray = new Type[types.length];
                    for (int i = 0; i < types.length; i++) {
                        typeArray[i] = signClass(types[i]);
                    }
                    return toParameterizedType(raw, typeArray);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }


        }

        //Ljava/lang/String;
        //Ljava/util/Map<
        //[B

        //var s=  "Ljava/lang/String;Ljava/lang/Integer;";
        //System.out.println(Arrays.toString(s.split(";")));
        private static Class signClass(String typeSign) throws ClassNotFoundException {
            if ("[B".equals(typeSign)) {
                return byte[].class;
            }
            if (typeSign.startsWith("L")) {
                typeSign = typeSign.substring(1);
            }
            if (typeSign.endsWith(";")) {
                typeSign = typeSign.substring(0, typeSign.length() - 1);
            }
            var clz= Class.forName(typeSign.replace('/', '.'));
    //        System.out.println(clz);
            return clz;
        }

        private static ParameterizedType toParameterizedType(Class base, Type... paramTypes) {
            return new ParameterizedTypeImpl(paramTypes, base.getDeclaringClass(), base);
        }
    }


    public static Type findRpcResultGenericType(Method method) {
        var tp = (ParameterizedType) method.getGenericReturnType();

        assert tp.getRawType() == RpcResult.class;

        return  tp.getActualTypeArguments()[0];
    }

    public static List<MethodStub> toRpcMethods(String appName,Class clz) {

        RpcService grpcServive = (RpcService) clz.getDeclaredAnnotation(RpcService.class);
        String rpcServiceName = rpcServiceName(appName,clz);

        return Stream.of(clz.getMethods())
                .filter(m -> m.getReturnType() == RpcResult.class && m.getParameterCount() <= 1)
                .map(m -> new MethodStub(grpcServive,rpcServiceName, m))
                .collect(Collectors.toList());

    }

    public static final char HIDDEN_SERVICE = '-';
    public static String rpcServiceName(String appName,Class clz) {

        //var pac =  clz.getPackageName();
        //if(TRIM_SERVICE_NAME && pac.startsWith("com.")){
        //    pac = pac.substring(4);
        //}
        var name = clz.getSimpleName();
        int begin = 0 , end = name.length();
        if(name.startsWith("I")){
            begin = 1;
        }
        if(name.endsWith("Service")){
            end -= "Service".length();
        }else if(name.endsWith("Rpc")){
            end -= "Rpc".length();
        }
        name = name.substring(begin,end);

        var fullName = appName + "/" + name;
        if(! clz.isAnnotationPresent(UnsafeWeb.class) ){
            // use - prefix to hidden the service
            fullName = HIDDEN_SERVICE + fullName;
        }
        return fullName ;
    }
    //
    //public static void main(String[] args) {
    //    toRpcMethods("test", RpcMetaService.class
    //    );
    //}

    public static boolean needValidator(Class dto){

        var clz = dto;
        do {
            for (var f : clz.getDeclaredFields()) {
                for (var anno : f.getAnnotations()) {
                    if (anno.annotationType().getName().startsWith("javax.validation")) {
                        return true;
                    }
                }
            }
            clz = clz.getSuperclass();
        }while (clz != Object.class);

        return false;
    }
}
