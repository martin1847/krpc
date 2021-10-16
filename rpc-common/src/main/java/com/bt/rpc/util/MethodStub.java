package com.bt.rpc.util;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.bt.rpc.annotation.Cached;
import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import com.google.protobuf.ByteString;
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

    public final Type                                     returnType;
    public final BiConsumer<Object[], InputProto.Builder> writeInput;
    public final Function<OutputProto, Object>            readOutput;
    public final Function<InputProto, Object>             readInput;

    public final BiConsumer<Object, OutputProto.Builder>   writeOutput;
    public final MethodDescriptor<InputProto, OutputProto> methodDescriptor;

    public final Method method;

    public final Cached cached;

    public final RpcService rpcService;

    @Getter
    @Setter
    int expireSeconds;


    public MethodStub(RpcService rpcService, String clzName, Method method) {
        this.method = method;
        this.rpcService = rpcService;

        if (method.isAnnotationPresent(Cached.class)) {
            cached = method.getAnnotation(Cached.class);
        } else {
            cached = null;
        }

        var types = method.getParameterTypes();

        if (types.length == 0) {
            readInput = null;
            writeInput = (objects, builder) -> {
            };
        } else {

            readInput = in -> JSON.parse(in.getJson(), types[0]);
            //SerializationUtils.deserialize(in.getB(),types[0]);
            writeInput = (objects, builder) -> builder.setJson(JSON.stringify(objects[0]));
            //{
            //    builder.setSe(SerEnum.JSON);
            //    builder.setB(SerializationUtils.serialize(objects[0]));
            //};
        }

        this.returnType = RefUtils.findRpcResultGenericType(method);
        if (byte[].class != returnType) {
            writeOutput = (obj, output) -> output.setJson(JSON.stringify(obj));
            //{
            //    output.setSe(SerEnum.JSON);
            //    output.setB(SerializationUtils.serialize(obj));
            //};
            readOutput = output -> JSON.parse(output.getJson(), returnType);
            //SerializationUtils.deserialize(output.getB(), returnType);
        } else {
            writeOutput = (obj, output) -> output.setBs(ByteString.copyFrom((byte[]) obj));
            readOutput = o -> o.getBs().toByteArray();
        }
        methodDescriptor = buildMd(clzName, method.getName());

    }

    public static MethodDescriptor<InputProto, OutputProto> buildMd(String service, String method) {
        var methodName = service + "/" + method;
        return MethodDescriptor
                .newBuilder(REQUEST_MARSHALLER, RESPONSE_MARSHALLER)
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(methodName).build();
    }

}
