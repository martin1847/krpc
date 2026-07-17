/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

/**
 * 获取环境变量，以及当前部署环境
 *
 * @author Martin.C
 * @version 2021/10/11 10:05 AM
 */
@Slf4j
public abstract class EnvUtils {

    public enum AppEnv {
        DEV, TEST, STAGING, PROD;
    }


    public static final String APP_ENV = "APP_ENV";

    // Warn at most once per JVM: APP_ENV is fixed for the process lifetime, so an
    // unknown value would otherwise log identically on every current() call.
    private static final AtomicBoolean UNKNOWN_ENV_WARNED = new AtomicBoolean(false);

    public static AppEnv current(){
        return normalize(env(APP_ENV, AppEnv.DEV.name()));
    }

    /**
     * Case-insensitive parse of the {@code APP_ENV} deployment label with alias
     * normalisation. This method NEVER throws:
     * <ul>
     *   <li>null / blank (APP_ENV unset) → {@link AppEnv#DEV} (smoothest for local dev).</li>
     *   <li>aliases: {@code pre}/{@code stage} → STAGING, {@code production} → PROD,
     *       {@code develop}/{@code development} → DEV.</li>
     *   <li>unknown value → warn once + fall back to {@link AppEnv#PROD} (safe side,
     *       same fail-closed philosophy as auth). Previously this threw
     *       {@link IllegalArgumentException} and crashed startup (banner display path).</li>
     * </ul>
     */
    static AppEnv normalize(String raw) {
        if (StringUtils.isBlank(raw)) {
            return AppEnv.DEV;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "dev":
            case "develop":
            case "development":
                return AppEnv.DEV;
            case "test":
                return AppEnv.TEST;
            case "staging":
            case "stage":
            case "pre":
                return AppEnv.STAGING;
            case "prod":
            case "production":
                return AppEnv.PROD;
            default:
                if (UNKNOWN_ENV_WARNED.compareAndSet(false, true)) {
                    log.warn("Unknown APP_ENV value '{}', falling back to PROD (safe side). "
                            + "Valid: dev/test/staging/prod (aliases: pre|stage->staging, "
                            + "production->prod, develop|development->dev).", raw);
                }
                return AppEnv.PROD;
        }
    }


    public static String env(String key,String defaultValue){
        var value = System.getenv(key);
        if(StringUtils.isNotBlank(value)){
            return value;
        }
        return defaultValue;
    }

    /** BIND-HOST: system property {@code rpc.server.bindHost}. */
    public static final String BIND_HOST_PROP = "rpc.server.bindHost";
    /** BIND-HOST: environment variable {@code KRPC_BIND_HOST} (aligns with the KRPC_* env family). */
    public static final String BIND_HOST_ENV = "KRPC_BIND_HOST";

    /**
     * BIND-HOST: resolve the server listen address for both faces (gRPC {@code RpcServerBuilder},
     * krpc-http {@code HttpServer}). Same dual-read + precedence discipline as the OTEL kill-switch
     * (ADR-0006 {@code KrpcOtel}: {@code System.getProperty("rpc.otel.enabled")} wins over
     * {@code System.getenv("KRPC_OTEL")}) — chosen over the SmallRye {@code @ConfigProperty}
     * variant (KRPC_MCP) because both bind sites live in the netty-core layer, below any Quarkus
     * config. System property {@code rpc.server.bindHost} wins over env {@code KRPC_BIND_HOST}.
     *
     * <p>NS-6 default-off: unset/blank on both → {@code null} = the current wildcard bind
     * ({@code ServerBuilder.forPort} / {@code bind(port)}), i.e. zero behaviour change. Set → both
     * faces bind exactly that address.
     */
    public static String bindHost() {
        var prop = System.getProperty(BIND_HOST_PROP);
        if (StringUtils.isNotBlank(prop)) {
            return prop.trim();
        }
        var env = System.getenv(BIND_HOST_ENV);
        if (StringUtils.isNotBlank(env)) {
            return env.trim();
        }
        return null;
    }


    public static String hostName(){
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            //e.printStackTrace();
            // ignore
            return "UnknownHostException";
        }
    }
}
