package com.bt.rpc.common;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 2020-04-28 16:52
 *
 * @author Martin.C
 */
public interface RpcConstants {

    String VERSION = "1.0.0";

    String INNER_PROVIDER = "GRPC";

    String CLIENT = "client";

    String SERVER = "server";

    String VENDOR = "java";

    //利用graalVM特性，缓存构建信息
    String CI_BUILD_ID = System.getProperty("ci.build")+"-"+ LocalDate.now(ZoneId.of("Asia/Shanghai"))+' '
            + LocalTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("HH:mm"));
            //+"-"+ System.getenv("CI_COMMIT_SHORT_SHA");
    //+"-"+;

    int DEFAULT_PORT = 50051;

    //int HTTP1_PORT = 80;

    /// use in app.yaml
    //String CONFIG_PLUGIN_DOMAIN = "plugin";
}
