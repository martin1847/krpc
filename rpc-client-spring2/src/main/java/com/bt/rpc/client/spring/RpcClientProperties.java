/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package com.bt.rpc.client.spring;

import java.util.Map;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import static com.bt.rpc.client.spring.RpcClientProperties.PREFIX;

/**
 * https://cloud.tencent.com/developer/article/1897285
 * @author martin
 * @version 2023/12/12 16:16
 */

@Data
@ConfigurationProperties(prefix = PREFIX)
public class RpcClientProperties {

    public static final String PREFIX = "rpc";

    private Map<String, ClientCfg> clients;

    @Data
    public static class ClientCfg {

        /**
         * http://common-cdn.common:50051
         */
        String url;

        /**
         * com.zlkj.common.cdn.service
         * 包名，多个逗号分隔
         */
        String scan;
    }

}