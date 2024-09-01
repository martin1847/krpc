/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.serial;

import tech.krpc.internal.InputProto.Builder;

/**
 *
 * @author martin.cong
 * @version 2021-10-17 10:54
 */
public interface ClientWriter {

    void writeParameters(Object[] obj, Builder builder);

    /**
     * only for bare byte[]. ignore the
     */
    //ClientWriter BYTES = (obj, bd) ->  bd.setBs(ByteString.copyFrom((byte[]) obj[0]));
    ClientWriter BYTES = (obj, bd) ->  bd.setBs((byte[]) obj[0]);

    /**
     * only for bare byte[]. ignore the
     */
    ClientWriter ZERO_INPUT = (obj, bd) ->  {};

    ClientWriter BY_USER = (obj, bd) -> Serial.Instance.get(bd.getEValue()).writeInput(obj[0],bd);
}