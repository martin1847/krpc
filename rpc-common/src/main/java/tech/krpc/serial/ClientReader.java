/**
 * Alipay.com Inc.
 * Copyright (c) 2004-2021 All Rights Reserved.
 */
package tech.krpc.serial;

import java.lang.reflect.ParameterizedType;

import tech.krpc.internal.OutputProto;

/**
 *
 * @author martin.cong
 */
public interface ClientReader<T> {


    T  readOutput(Serial serial,  OutputProto proto);


    class Normal<T> implements ClientReader<T> {

        final Class<T> returnType;

        public Normal(Class<T> returnType) {
            this.returnType = returnType;
        }

        @Override
        public T readOutput(Serial serial,OutputProto proto) {
            return serial.readOutput(proto, returnType);
        }
    }

    class Generic<T> implements ClientReader<T> {

        final ParameterizedType returnType;

        public Generic(ParameterizedType returnType) {
            this.returnType = returnType;
        }

        @Override
        public T readOutput(Serial serial,OutputProto proto) {
            return serial.readOutput(proto, returnType);
        }
    }

    ClientReader<byte[]> BARE = (serial, proto) -> proto.getBs();

}