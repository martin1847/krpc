/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.serial;

import java.lang.reflect.ParameterizedType;

import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.InputProto.Builder;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.internal.SerialEnum;
import com.bt.rpc.util.JsonUtils;

/**
 *
 * @author martin.cong
 * @version 2021-10-17 13:10
 */
public class JsonSerial  implements Serial{
    @Override
    public void writeInput(Object arg, Builder builder) {
        builder.setUtf8(JsonUtils.stringify(arg));
    }

    @Override
    public <T> T readOutput(OutputProto proto, Class<T> type) {
        return JsonUtils.parse(proto.getUtf8(), type);
    }

    @Override
    public <T> T readOutput(OutputProto proto, ParameterizedType type) {
        return JsonUtils.parse(proto.getUtf8(), type);
    }

    @Override
    public <T> T readInput(InputProto proto, Class<T> type) {
        return JsonUtils.parse(proto.getUtf8(), type);
    }

    @Override
    public <T> T readInput(InputProto proto, ParameterizedType type) {
        return JsonUtils.parse(proto.getUtf8(), type);
    }

    @Override
    public SerialEnum id() {
        return SerialEnum.JSON;
    }

    @Override
    public void writeOutput(Object obj, OutputProto.Builder out) {
        if(obj.getClass() == byte[].class) {
            System.out.println("Server write got bytes ....");
        }
        out.setUtf8(JsonUtils.stringify(obj));
    }
}