package com.bt.rpc;

import java.io.IOException;

import com.bt.rpc.client.GeneralizeClient;
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