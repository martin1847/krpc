package com.bt.rpc.common;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import com.bt.rpc.annotation.Cached;
import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.util.RefUtils;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.lite.ProtoLiteUtils;
import lombok.Getter;
import lombok.Setter;

/**
 * 2020-01-09 15:33
 *
 * @author Martin.C
 */

public class MethodStub {

    private final static MethodDescriptor.Marshaller<InputProto>  REQUEST_MARSHALLER  =
            ProtoLiteUtils.marshaller(InputProto.getDefaultInstance());
    private final static MethodDescriptor.Marshaller<OutputProto> RESPONSE_MARSHALLER =
            ProtoLiteUtils.marshaller(OutputProto.getDefaultInstance());

    public final Type                                      returnType;
    public final MethodDescriptor<InputProto, OutputProto> methodDescriptor;

    public final Method method;

    public final Cached cached;

    public final RpcService rpcService;

    @Getter
    @Setter
    int expireSeconds;

    public MethodStub(RpcService rpcService, String rpcServiceName, Method method) {
        this.method = method;
        this.rpcService = rpcService;

        if (method.isAnnotationPresent(Cached.class)) {
            cached = method.getAnnotation(Cached.class);
        } else {
            cached = null;
        }

        this.returnType = RefUtils.findRpcResultGenericType(method);
        methodDescriptor = buildMd(rpcServiceName+ "/" +method.getName());

    }

    public static MethodDescriptor<InputProto, OutputProto> buildMd(String fullMethodName) {
        return MethodDescriptor
                .newBuilder(REQUEST_MARSHALLER, RESPONSE_MARSHALLER)
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(fullMethodName).build();
    }

    //public static void main(String[] args) {
    //    byte[] bytes = new byte[]{12,34};
    //    Class<byte[]> bytesCls = byte[].class;
    //    System.out.println(bytes.getClass() == bytesCls);
    //}
}
