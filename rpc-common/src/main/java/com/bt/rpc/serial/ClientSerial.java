/**
 * Alipay.com Inc.
 * Copyright (c) 2004-2021 All Rights Reserved.
 */
package com.bt.rpc.serial;

import java.lang.reflect.ParameterizedType;

import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;

/**
 *
 * @author martin.cong
 */
public interface ClientSerial<T> {

    default void writeInput(Object[] args, InputProto.Builder builder){
        Serial.Instance.get().writeInput(args,builder);
    }

    T  readOutput(OutputProto proto);


    class Normal<T> implements ClientSerial<T>{

        final Class<T> returnType;

        public Normal(Class<T> returnType) {
            this.returnType = returnType;
        }

        @Override
        public T readOutput(OutputProto proto) {
            return Serial.Instance.get().readOutput(proto, returnType);
        }
    }

    class Generic<T> implements ClientSerial<T>{

        final ParameterizedType returnType;

        public Generic(ParameterizedType returnType) {
            this.returnType = returnType;
        }

        @Override
        public T readOutput(OutputProto proto) {
            return Serial.Instance.get().readOutput(proto, returnType);
        }
    }

    ClientSerial<byte[]> BARE = proto -> proto.getBs().toByteArray();

}