/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.serial;

import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.internal.OutputProto.Builder;
import com.google.protobuf.ByteString;

/**
 *
 * @author martin.cong
 * @version 2021-10-17 10:54
 */
public interface ServerSerial {

    <T> T  readInput(InputProto proto, Class<T> type);

    void writeOutput(Object obj, OutputProto.Builder out);

    //ServerSerial JSON = (obj, out) -> out.setJson(JsonUtils.stringify(obj));

    /**
     * only for bare byte[].
     */
    ServerSerial BARE = new ServerSerial() {
        @Override
        public <T> T readInput(InputProto proto, Class<T> type) {
            return Serial.Instance.get().readInput(proto,type);
        }

        @Override
        public void writeOutput(Object obj, Builder out) {
            out.setBs(ByteString.copyFrom((byte[]) obj));
        }
    };
}