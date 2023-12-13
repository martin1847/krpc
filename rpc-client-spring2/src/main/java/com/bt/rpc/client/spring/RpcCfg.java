/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package com.bt.rpc.client.spring;

import lombok.Data;

/**
 *
 * @author martin
 * @version 2023/12/13 09:56
 */
@Data
public class RpcCfg {
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