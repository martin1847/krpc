/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.serial;

import com.bt.rpc.internal.OutputProto;

/**
 *
 * @author martin.cong
 * @version 2021-10-17 10:54
 */
public interface ServerWriter {

    //<T> T  readInput(InputProto proto, Class<T> type);

    void writeOutput(Object obj, OutputProto.Builder out);

    //default void writeOutput(byte[] obj, OutputProto.Builder out){
    //
    //    System.out.println("Server write bytes ....");
    //
    //    out.setBs(ByteString.copyFrom(obj));
    //}


    /**
     * only for bare byte[]. ignore the
     */
    ServerWriter BYTES = (obj, out) ->  out.setBs((byte[]) obj);
}