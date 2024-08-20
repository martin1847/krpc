/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2024 All Rights Reserved.
 */
package test.java_client;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import tech.krpc.client.GeneralizeClient;

/**
 *
 * @author martin
 * @version 2024/04/23 23:14
 */
public class TestGeneralizeClient {

    public static void main(String[] args) {

        var addr = "10.17.1.10";
        addr ="127.0.0.1";
        ManagedChannel channel =  ManagedChannelBuilder.forAddress(addr,50051).usePlaintext().build();
        var proto = GeneralizeClient.call(channel,"-foreign/Image/captcha","\"Hello,123,60\"");
        //System.out.println(Arrays.toString());

        var byteArray = proto.getBs();
         var filePath = "/Users/martin/Downloads/test3.jpg";
        try (var fos = new FileOutputStream(filePath)) {
            // 将字节数组写入文件
            fos.write(byteArray);
            System.out.println("字节数组已成功写入文件: " + filePath);
        } catch (IOException e) {
            // 处理可能的 IO 异常
            e.printStackTrace();
        }

    }

    static void testRpc(){
        ManagedChannel channel =  ManagedChannelBuilder.forAddress("10.17.1.10",20051).usePlaintext().build();
        GeneralizeClient.call(channel,"-common-cdn/Cdn/testHttp","{\"url\":\"https://www.baidu.com\",\"type\":2}");

        channel =  ManagedChannelBuilder.forAddress("iwytest.wangyuedaojia.com",443).useTransportSecurity().build();
        GeneralizeClient.call(channel,"wy/Home/diamond","\"320200\"");
    }
}