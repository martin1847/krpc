/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc;

import java.net.URL;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 *
 * @author Martin.C
 * @version 2021/11/04 4:18 PM
 */
@Command(name = "rpcurl", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "测试rpc服务")
public class RpcUrl implements Runnable{

    static final String META_SERVICE = "RpcMeta";
    static final String DEFAULT_METHOD = "listApis";

    @Option(names = {"-u", "--url"}, description = "服务地址，如: https://example-api.botaoyx.com")
    URL url;

    @Option(names ={"-L", "--no-url"},description = "本机测试 url=http://host.docker.internal:50051")
    boolean localhost;

    @Option(names =  {"-W", "--no-web"},description = "测试非 UnsafeWeb 服务")
    boolean noWeb;

    @Option(names = {"-P", "--no-pretty"},description = "NO pretty json")
    boolean noPretty;

    @Option(names = {"-a", "--app"},required = true,description = "项目名,如 demo-java-server")
    String app;

    @Option(names = {"-s", "--service"},description = "服务名，默认: "+ META_SERVICE,defaultValue = META_SERVICE)
    String service;

    @Option(names = {"-m", "--method"},description = "方法名,默认: "+DEFAULT_METHOD,defaultValue = DEFAULT_METHOD)
    String method;

    @Option(names = {"-d", "--data"},description = "入参json，如 -d '{\"name\":\"rpcurl\"}'")
    String input;



    @Override
    public void run() {


        try {
            if(localhost) {
                url = new URL("http://host.docker.internal:50051");
            }

            if(null == url){
                System.err.println("host is required or use -L ");
            }

            if(META_SERVICE.equals(service)){
                noWeb = true;
            }

            if(noWeb && '-' != app.charAt(0)){
                app = "-"+app;
            }


            RpcCall.call(this);


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String toFullMethod(){
        return app+"/"+service+"/"+method;
    }
}