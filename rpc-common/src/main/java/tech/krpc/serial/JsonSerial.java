/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.serial;

import java.lang.reflect.ParameterizedType;

import tech.krpc.internal.InputProto;
import tech.krpc.internal.InputProto.Builder;
import tech.krpc.internal.OutputProto;
import tech.krpc.internal.SerialEnum;
import tech.krpc.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author martin.cong
 * @version 2021-10-17 13:10
 */
@Slf4j
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
        var utf8 = proto.getUtf8();
        if(utf8.isEmpty()){
            return null;
        }
        return JsonUtils.parse(utf8, type);
    }

    @Override
    public <T> T readInput(InputProto proto, ParameterizedType type) {
        var utf8 = proto.getUtf8();
        if(utf8.isEmpty()){
            return null;
        }
        return JsonUtils.parse(utf8, type);
    }

    @Override
    public SerialEnum id() {
        return SerialEnum.JSON;
    }

    @Override
    public void writeOutput(Object obj, OutputProto.Builder out) {
        //if(obj.getClass() == byte[].class) {
        //    log.debug("Server write got bytes ....");
        //}
        out.setUtf8(JsonUtils.stringify(obj));
    }
}