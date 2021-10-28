package com.bt.mybatis.type;

import java.util.List;

import com.bt.rpc.util.JsonUtils;

/**
 * date: 16/8/15 16:14
 *
 * @author: yangyang.cyy@alibaba-inc.com
 */
public abstract class JsonListHandler<T> extends JsonTypeHandler<List<T>> {

    private final Class<T> tClass;

    {
        tClass = (Class<T>) getParameterizedTypes(this)[0];
    }

    @Override
    protected List<T> parseJSON(String json) {
        return JsonUtils.parseArray(json, tClass);
    }
}
