/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.serial;

import java.lang.reflect.ParameterizedType;

import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import tech.krpc.internal.SerialEnum;
import tech.krpc.internal.InputProto.Builder;

/**
 *
 * @author martin.cong
 * @version 2021-10-17 13:01
 */
public interface Serial  extends ServerWriter{

    void writeInput(Object obj, Builder builder);

    <T> T readOutput(OutputProto proto , Class<T> type) ;

    <T> T readOutput(OutputProto proto , ParameterizedType type) ;


    // server side
    <T> T  readInput(InputProto proto, Class<T> type);

    <T> T  readInput(InputProto proto, ParameterizedType type);

    SerialEnum id();


    class Instance{
        static final   Serial JSON =  new JsonSerial();

        static final SerialEnum[] SUPPORTED = {SerialEnum.JSON};
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

        public static SerialEnum[] supported(){
            return SUPPORTED;
        }

    }

}