package com.bt.rpc.client;

import java.util.Base64;

import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.util.JsonUtils;
import com.bt.rpc.common.MethodStub;
import com.google.protobuf.ByteString;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCalls;

/**
 * generalize rpc call
 * 2020-04-28 16:07
 *
 * @author Martin.C
 */
public class GeneralizeClient {

    // TODO  ManagedChannel may use a simple time-based cache in admin, for example 5 mitunes
    // ConcurrentMap<Key, Graph>  GuavaMap = new  MapMaker()
    //   .softKeys()
    //   .weakValues()
    //   .maximumSize(10000)
    //   .expiration(10, TimeUnit.MINUTES)
    public static OutputProto call(ManagedChannel channel, String service, String method, String inputJson) {

        assert service != null;
        assert method != null;

        // may use lru cache
        var md = MethodStub.buildMd(service, method);

        var input = InputProto.newBuilder();
        if (null != inputJson && inputJson.length() > 0) {
            input.setJson(inputJson);
        }
        var call = channel.newCall(md, CallOptions.DEFAULT);

        return ClientCalls.blockingUnaryCall(call, input.build());
    }

    public static String toJson(OutputProto outout) {
        var sb = new StringBuilder(200);
        sb.append("{\"code\":").append(outout.getC());//.append("\"");
        var message = outout.getM();
        if (null != message && message.length() > 0) {
            sb.append(",\"message\":").append(JsonUtils.stringify(message));
        }
        String data = null;

        if (outout.hasJson()) {
            data = outout.getJson();
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
