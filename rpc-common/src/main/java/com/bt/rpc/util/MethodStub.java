package com.bt.rpc.util;

import com.bt.rpc.annotation.Cached;
import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.internal.InputMessage;
import com.bt.rpc.internal.OutputMessage;
import com.bt.rpc.internal.SerEnum;
import com.google.protobuf.ByteString;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.lite.ProtoLiteUtils;
import lombok.Getter;
import lombok.Setter;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 2020-01-09 15:33
 *
 * @author Martin.C
 */

public class MethodStub {


//    private final static MethodDescriptor.Marshaller<InputMessage> REQUEST_MARSHALLER =
//            new MessageMarshaller<>(InputMessage.getDefaultInstance());
//    private final static MethodDescriptor.Marshaller<OutputMessage> RESPONSE_MARSHALLER =
//            new MessageMarshaller<>(OutputMessage.getDefaultInstance());


    private final static MethodDescriptor.Marshaller<InputMessage>  REQUEST_MARSHALLER  =
            ProtoLiteUtils.marshaller(InputMessage.getDefaultInstance());
    private final static MethodDescriptor.Marshaller<OutputMessage> RESPONSE_MARSHALLER =
            ProtoLiteUtils.marshaller(OutputMessage.getDefaultInstance());


    //public final ClientCall<InputMessage, OutputMessage> clientCall;
    public final Type returnType;
    public final BiConsumer<Object[],InputMessage.Builder> writeInput;
    public final Function<OutputMessage,Object> readOutput;
    public final Function<InputMessage,Object> readInput;

    public final BiConsumer<Object, OutputMessage.Builder> writeOutput;
    public final MethodDescriptor<InputMessage, OutputMessage> methodDescriptor;

    public  final  Method method;

    public final Cached cached ;

    public final RpcService rpcService;

    @Getter@Setter
    int expireSeconds;

//    public int getExpireSeconds() {
//        return expireSeconds;
//    }

    public MethodStub(RpcService rpcService, String clzName , Method method){
        this.method  = method;
        this.rpcService = rpcService;

        if(method.isAnnotationPresent(Cached.class)){
            cached = method.getAnnotation(Cached.class);
        }else {
            cached = null;
        }

        var types = method.getParameterTypes();


        if(types.length == 0){
            readInput = null;
            writeInput = (objects, builder) -> {};
        }else{
            readInput = in -> SerializationUtils.deserialize(in.getB(),types[0]);
            writeInput = (objects, builder) -> {
                builder.setSe(SerEnum.JSON);
                builder.setB(SerializationUtils.serialize(objects[0]));
            };
        }

        this.returnType = RefUtils.findRpcResultGenericType(method);
        if(returnType == String.class){
            writeOutput =  (obj,output)-> output.setS((String)obj);
            readOutput = OutputMessage::getS;
        }else if(returnType == byte[].class){
            writeOutput =  (obj,output)-> output.setB( ByteString.copyFrom((byte[])obj));
            readOutput = o->o.getB().toByteArray();
        }else{
            writeOutput =  (obj,output)->{
                output.setSe(SerEnum.JSON);
                output.setB(SerializationUtils.serialize(obj));
            };
            readOutput = output -> SerializationUtils.deserialize(output.getB(), returnType);
        }
        methodDescriptor = buildMd(clzName,  method.getName());

    }


    public  static MethodDescriptor<InputMessage, OutputMessage> buildMd(String service,String method){
        var methodName = service+ "/" + method;
		return MethodDescriptor
                .newBuilder(REQUEST_MARSHALLER,RESPONSE_MARSHALLER)
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(methodName).build();
    }


}
