/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.typehandler;

import java.util.List;

import com.bt.mybatis.type.JsonListHandler;
import com.bt.rpc.util.JsonUtils;

/**
 *
 * @author Martin.C
 * @version 2021/10/28 6:15 PM
 */
public class Json2IntList  extends JsonListHandler<Integer> {
    @Override
    protected String stringify(List<Integer> obj) {
        return JsonUtils.stringify(obj);
    }

    @Override
    protected List<Integer> parseJSON(String json) {
        return JsonUtils.parseArray(json,Integer.class);
    }
}