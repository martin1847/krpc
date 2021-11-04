package com.bt.rpc;

import java.io.IOException;
import java.util.Arrays;

import com.bt.rpc.client.GeneralizeClient;
import com.bt.rpc.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class HelloWorldMain implements QuarkusApplication {
    @Override
    public int run(String... args) throws Exception {
        var channelBuilder =
                ManagedChannelBuilder.forAddress("example-api.botaoyx.com", 443);
        var tls = true;
        if (tls) {
            channelBuilder.useTransportSecurity();
        } else {
            channelBuilder.usePlaintext();
        }
        var channel = channelBuilder.build();

        var res = GeneralizeClient.call(channel, "demo-java-server/RpcMetaService/listApis", null);
        var json = GeneralizeClient.toJson(res);
        formatJsonString(json);
        System.out.println("Hello  :" + Arrays.toString(args));
        return 0;
    }

    private static void formatJsonString(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            Object jsonObject = mapper.readValue(json, Object.class);
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
            System.out.println(prettyJson);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}