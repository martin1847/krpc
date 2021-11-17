package com.bt.rpc.client;

import java.util.Base64;
import java.util.function.Consumer;

import com.bt.rpc.common.MethodStub;
import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.util.JsonUtils;
import com.google.protobuf.ByteString;
import io.grpc.CallOptions;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.stub.ClientCalls;

/**
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
            public void start(Listener responseListener, Metadata headers) {
                //headers.put(AUTHORIZATION, "Bearer eyJhbGciOiJFUzI1NiIsImtpZCI6ImJvLXRlc3QtMjExMSJ9.eyJzdWIiOiIxMDAxIiwiYWRtIjoxLCJleHAiOjE2MzcxNDY2MjN9.GiXQqoNckNAn8UiXDW9BSoaPQPuS4SNJFTz1pQzmeP0PSkdo05zoYRYE2KDW6rm-eEleVEy49LK9U4g7DH5ghQ");
                cutomerHeaders.accept(headers);
                super.start(responseListener, headers);
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
            ByteString payload = outout.getBs();
            data = JsonUtils.stringify(
                    Base64.getEncoder().encodeToString(payload.toByteArray()));
        }
        sb.append(",\"data\":").append(data);
        sb.append('}');
        return sb.toString();
    }

}
