/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 获取环境变量，以及当前部署环境
 *
 * @author Martin.C
 * @version 2021/10/11 10:05 AM
 */
public abstract class EnvUtils {

    public enum AppEnv {
        DEV,TEST,PRE,PROD;
    }


    public static final String APP_ENV = "APP_ENV";

    public static AppEnv current(){
        var env = env(APP_ENV,AppEnv.DEV.name());
        return AppEnv.valueOf(env.toUpperCase(Locale.ROOT));
    }



    public static String env(String key,String defaultValue){
        var value = System.getenv(key);
        if(StringUtils.isNotBlank(value)){
            return value;
        }
        return defaultValue;
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