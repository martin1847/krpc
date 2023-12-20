/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package com.bt.rpc.common.proto;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.bt.rpc.internal.InputProto;
import io.grpc.MethodDescriptor.Marshaller;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author martin
 * @version 2023/12/18 11:45
 */
@Slf4j
public class InputMarshaller implements Marshaller<InputProto> {

    static final byte[] DATA_2_UTF8_TAG = ProtoWriter.streamTag(2,ProtoWriter.LENGTH_DELIMITED_WIRE_TYPE);
    static final byte[] DATA_3_BYTES_TAG = ProtoWriter.streamTag(3,ProtoWriter.LENGTH_DELIMITED_WIRE_TYPE);

    static final byte[] DATA_1_E_TAG = ProtoWriter.streamTag(1,ProtoWriter.VARINT_WIRE_TYPE);

    @Override
    public InputStream stream(InputProto proto) {

        if(proto.getEValue() == 0){//json
            if(proto.hasUtf8()){
                return OutputMarshaller.tagLengthDelimitedAsStream(DATA_2_UTF8_TAG,proto.getUtf8().getBytes(StandardCharsets.UTF_8));
            }
            return OutputMarshaller.tagLengthDelimitedAsStream(DATA_3_BYTES_TAG,proto.getBs());
        }

        log.debug("got proto use NONE json SerialEnum  {}  , size   ",proto.getE());

        var eBytes = ProtoWriter.streamUInt32(proto.getEValue());
        //var size = proto.getSerializedSize();
        //var writer = new ProtoWriter(size);
        //writer.writeUInt32(1,proto.getEValue());
        if(proto.hasUtf8()){
            var msgBytes = proto.getUtf8().getBytes(StandardCharsets.UTF_8);
            var msgLenBytes = ProtoWriter.streamUInt32(msgBytes.length);
            var buf = Unpooled.wrappedBuffer(DATA_1_E_TAG,eBytes,DATA_2_UTF8_TAG,msgLenBytes,msgBytes);
            return new ByteBufInputStream(buf);
        }else {
            var msgBytes = proto.getBs();
            var msgLenBytes = ProtoWriter.streamUInt32(msgBytes.length);
            var buf = Unpooled.wrappedBuffer(DATA_1_E_TAG,eBytes,DATA_3_BYTES_TAG,msgLenBytes,msgBytes);
            return new ByteBufInputStream(buf);
        }
    }

    @SneakyThrows
    @Override
    public InputProto parse(InputStream inputStream) {
        return new InputProto(inputStream);
    }
}