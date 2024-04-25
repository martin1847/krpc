package com.bt.rpc.client;

import java.util.Base64;
import java.util.function.Consumer;

import tech.krpc.common.MethodStub;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import tech.krpc.util.JsonUtils;
import io.grpc.CallOptions;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.ClientCalls;

import static io.grpc.Metadata.BINARY_HEADER_SUFFIX;

/**
 *
 * https://github.com/grpc/grpc-java/blob/master/examples/src/main/java/io/grpc/examples/header/HeaderClientInterceptor.java
 *
 * generalize rpc call
 * 2020-04-28 16:07
 *
 * @author Martin.C
 */
public class GeneralizeClient {


    static final Consumer<Metadata> EMPTY = metadata -> {};

    public static OutputProto call(ManagedChannel channel, String fullRpcMethodName, String inputJson) {
        return call(channel,fullRpcMethodName,inputJson,EMPTY);
    }

    public static OutputProto call(ManagedChannel channel, String fullRpcMethodName, String inputJson, Consumer<Metadata> cutomerHeaders) {

        assert fullRpcMethodName != null;
        // may use lru cache
        var md = MethodStub.buildMd(fullRpcMethodName);

        var input = InputProto.newBuilder();
        if (null != inputJson && inputJson.length() > 0) {
            input.setUtf8(inputJson);
        }
        var call = channel.newCall(md, CallOptions.DEFAULT);

        var headerForwardCall = new SimpleForwardingClientCall<>(call){
            @Override
            public void start(Listener<OutputProto> responseListener, Metadata headers) {
                cutomerHeaders.accept(headers);
                //super.start(responseListener, headers);
                super.start(new SimpleForwardingClientCallListener<>(responseListener) {
                    @Override
                    public void onHeaders(Metadata resHeader) {
                        System.out.println();
                        System.out.println("* header received from server");
                        for(var key : resHeader.keys()){
                            System.out.print("< "+key+": ");
                            String value;
                            if (key.endsWith(BINARY_HEADER_SUFFIX)) {
                                value = Base64.getEncoder().encodeToString(
                                        resHeader.get(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER))
                                );
                            }else {
                                value = resHeader.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
                            }
                            System.out.println(value);
                        }
                        System.out.println();
                        super.onHeaders(resHeader);
                    }
                }, headers);
            }
        };
        return ClientCalls.blockingUnaryCall(headerForwardCall, input.build());
    }

    public static String toJson(OutputProto outout) {
        var sb = new StringBuilder(200);
        sb.append("{\"code\":").append(outout.getC());//.append("\"");
        var message = outout.getM();
        if (null != message && message.length() > 0) {
            sb.append(",\"message\":").append(JsonUtils.stringify(message));
        }
        String data = null;

        if (outout.hasUtf8()) {
            data = outout.getUtf8();
        } else {
            var payload = outout.getBs();
            data = JsonUtils.stringify(
                    Base64.getEncoder().encodeToString(payload));
        }
        sb.append(",\"data\":").append(data);
        sb.append('}');
        return sb.toString();
    }

}
