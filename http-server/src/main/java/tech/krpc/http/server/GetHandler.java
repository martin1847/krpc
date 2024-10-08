/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.http.server;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 *
 * @author Martin.C
 * @version 2021/12/22 9:24 AM
 */
public interface GetHandler extends Handler<QueryStringDecoder> {

    default String param(QueryStringDecoder query, String key) {
        var params = query.parameters().get(key);
        return null != params && params.size() == 1 ? params.get(0) : null;
    }
}