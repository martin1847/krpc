package com.bt.rpc.client;

import com.bt.rpc.internal.InputMessage;
import com.bt.rpc.internal.OutputMessage;
import com.bt.rpc.internal.SerEnum;
import com.google.protobuf.ByteString;
import com.bt.rpc.util.MethodStub;
import com.bt.rpc.util.SerializationUtils;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCalls;

import java.util.Base64;

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
    public static OutputMessage call(ManagedChannel channel, String service, String method, String inputJson){

        assert service !=null;
        assert method != null;

        // may use lru cache
        var md = MethodStub.buildMd(service, method);

        var input = InputMessage.newBuilder();
        if(null != inputJson && inputJson.length() >0){
            input.setSe(SerEnum.JSON);
            input.setB(ByteString.copyFromUtf8(inputJson));
        }
        var call = channel.newCall(md, CallOptions.DEFAULT);

        return ClientCalls.blockingUnaryCall(call, input.build());
    }


    public static String toJson(OutputMessage outout){
        var sb = new StringBuilder(200);
        sb.append("{\"code\":").append(outout.getC());//.append("\"");
        var message = outout.getM();
        if (null != message && message.length()>0)
        {
            sb.append(",\"message\":").append(SerializationUtils.toJson(message));
        }
        String data = null;
        ByteString payload = outout.getB();
        if (! payload.isEmpty())
        {
            data = outout.getSe() == SerEnum.JSON ?
                    payload.toStringUtf8() : SerializationUtils.toJson(
                            Base64.getEncoder().encodeToString(payload.toByteArray()));
        }
        else
        {
            var pl = outout.getS();
            if(null!=pl && pl.length()>0) {
                data = SerializationUtils.toJson(pl);
            }
        }
        if (data != null)
        {
            sb.append(",\"data\":").append(data);
        }
        sb.append('}');
        return sb.toString();
    }

}
