/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.serial;

import java.lang.reflect.ParameterizedType;

import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.InputProto.Builder;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.util.JsonUtils;

/**
 *
 * @author martin.cong
 * @version 2021-10-17 13:10
 */
public class JsonSerial  implements Serial{
    @Override
    public void writeInput(Object[] args, Builder builder) {
        builder.setJson(JsonUtils.stringify(args[0]));
    }

    @Override
    public <T> T readOutput(OutputProto proto, Class<T> type) {
        return JsonUtils.parse(proto.getJson(), type);
    }

    @Override
    public <T> T readOutput(OutputProto proto, ParameterizedType type) {
        return JsonUtils.parse(proto.getJson(), type);
    }

    @Override
    public <T> T readInput(InputProto proto, Class<T> type) {
        return JsonUtils.parse(proto.getJson(), type);
    }

    @Override
    public void writeOutput(Object obj, OutputProto.Builder out) {
        out.setJson(JsonUtils.stringify(obj));
    }
}