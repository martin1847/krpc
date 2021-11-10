package com.bt.rpc;

import java.io.IOException;

import com.bt.rpc.client.GeneralizeClient;
import com.bt.rpc.common.meta.ApiMeta;
import com.bt.rpc.model.RpcResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannelBuilder;

public class RpcCall {

    public static void call(RpcUrl rpc) throws IOException{
        var url = rpc.url;
        var channelBuilder =
                ManagedChannelBuilder.forAddress(url.getHost(), url.getPort()<0?url.getDefaultPort() : url.getPort());
        if ("https".equals(url.getProtocol())) {
            channelBuilder.useTransportSecurity();
        } else {
            channelBuilder.usePlaintext();
        }
        var channel = channelBuilder.build();

        var mapper = new ObjectMapper();
        var input = rpc.input;
        if(input!=null && ! input.isBlank()){
            mapper.readValue(input, Object.class);
        }
        System.out.println(url.toExternalForm()+"/"+rpc.toFullMethod());
        var res = GeneralizeClient.call(channel, rpc.toFullMethod(), input);
        var json = GeneralizeClient.toJson(res);
        if("listApis".equals(rpc.method)){
            var type = mapper.getTypeFactory().constructParametricType(
                    RpcResult.class, ApiMeta.class
            );
            RpcResult<ApiMeta> metaRpcResult = mapper.readValue(json,type);
            //TODO gen Typescript
            //System.out.println(metaRpcResult.getData());
        }
        if(rpc.noPretty){
            System.out.println(json);
        }else {
            formatJsonString(mapper,json);
        }
    }

    private static void formatJsonString(ObjectMapper mapper,String json) throws JsonProcessingException {
            Object jsonObject = mapper.readValue(json, Object.class);
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
            System.out.println(prettyJson);
    }

}