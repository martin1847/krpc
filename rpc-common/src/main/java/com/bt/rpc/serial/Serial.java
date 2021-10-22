/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.serial;

import java.lang.reflect.ParameterizedType;

import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.internal.SerialEnum;
import com.google.protobuf.ByteString;

/**
 *
 * @author martin.cong
 * @version 2021-10-17 13:01
 */
public interface Serial  extends ServerWriter{

    void writeInput(Object obj, InputProto.Builder builder);

    <T> T readOutput(OutputProto proto , Class<T> type) ;

    <T> T readOutput(OutputProto proto , ParameterizedType type) ;


    // server side
    // TODO server input support ParameterizedType
    <T> T  readInput(InputProto proto, Class<T> type);

    SerialEnum id();


    class Instance{
        static final   Serial JSON =  new JsonSerial();
        //TODO more than json
        public static Serial get(int seValue){
            if(seValue == SerialEnum.JSON_VALUE){
                return JSON;
            }
            throw new RuntimeException("Serial of [ "+ SerialEnum.forNumber(seValue) +" ] not support now!");
        }
        static Serial get(SerialEnum se){
            return get(se.getNumber());
        }
    }

}