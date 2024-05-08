package tech.krpc;

import java.io.IOException;

import tech.krpc.client.GeneralizeClient;
import tech.krpc.common.meta.ApiMeta;
import tech.krpc.model.RpcResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannelBuilder;

public class RpcCall {

    public static void call(RpcUrl rpc) throws IOException{
        var url = rpc.url;
        //if(url == null){
        //    url = rpc.u;
        //}
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
        System.out.println(url +" \t "+ url.getPath());//url.toExternalForm()+"/"+rpc.toFullMethod());
        var res = GeneralizeClient.call(channel, url.getPath().substring(1), input,rpc::customerHeader);
        var json = GeneralizeClient.toJson(res);

        if(rpc.noPretty){
            System.out.println(json);
        }else {
            formatJsonString(mapper,json);
        }

        //if(RpcUrl.DEFAULT_METHOD.equals(rpc.method)){
        //    var type = mapper.getTypeFactory().constructParametricType(
        //            RpcResult.class, ApiMeta.class
        //    );
        //    RpcResult<ApiMeta> metaRpcResult = mapper.readValue(json,type);
        //
        //    System.out.println("unsupport : "+ metaRpcResult);
        //    //TsClientBulider.buildTsFile(metaRpcResult.getData()).forEach((k, v) -> {
        //    //    System.out.println("-------------[  "+k+".ts  ]------------------" );
        //    //    System.out.println( k + ".ts");
        //    //    System.out.println();
        //    //    System.out.println(v);
        //    //    System.out.println();
        //    //});
        //}
    }

    private static void formatJsonString(ObjectMapper mapper,String json) throws JsonProcessingException {
            Object jsonObject = mapper.readValue(json, Object.class);
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
            System.out.println(prettyJson);
    }

}