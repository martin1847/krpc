package tech.krpc.common;

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

    // AGENT-002 F2: single source of truth — generated from Gradle project.version at build time
    // (see rpc-common/build.gradle generateBuildVersion). Never hand-maintained here.
    String VERSION = BuildVersion.VERSION;

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

    // ADR-0004 / D2 (2026-07-03): app-layer defence-in-depth for CVE-2026-47244
    // (HTTP/2 concurrent-stream DoS). Netty gRPC server caps concurrent calls per
    // connection; 0 = unlimited (pre-1.0.4 behaviour), negative = config error (fail-fast).
    // Consumers override via rpc.server.maxConcurrentCallsPerConnection.
    int DEFAULT_MAX_CONCURRENT_CALLS_PER_CONNECTION = 2000;

    //int HTTP1_PORT = 80;

    /// use in app.yaml
    //String CONFIG_PLUGIN_DOMAIN = "plugin";
}
