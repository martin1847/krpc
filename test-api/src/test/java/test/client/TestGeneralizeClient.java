/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2024 All Rights Reserved.
 */
package test.client;

import tech.krpc.client.GeneralizeClient;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/**
 *
 * @author martin
 * @version 2024/04/23 23:14
 */
public class TestGeneralizeClient {

    public static void main(String[] args) {

        ManagedChannel channel =  ManagedChannelBuilder.forAddress("10.17.1.10",20051).usePlaintext().build();
        GeneralizeClient.call(channel,"-common-cdn/Cdn/testHttp","{\"url\":\"https://www.baidu.com\",\"type\":2}");

        channel =  ManagedChannelBuilder.forAddress("iwytest.wangyuedaojia.com",443).useTransportSecurity().build();
        GeneralizeClient.call(channel,"wy/Home/diamond","\"320200\"");

    }
}