/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2025 All Rights Reserved.
 */
package tech.krpc.http.server;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 *
 * @author martin
 * @version 2025/04/30 17:58
 */
public interface QueryStringAware {

    void setQueryString(QueryStringDecoder queryStringDecoder);
}