/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package com.bt.rpc.common.proto;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.bt.rpc.internal.InputProto;
import com.google.protobuf.CodedOutputStream;
import io.grpc.MethodDescriptor.Marshaller;
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

    @Override
    public InputStream stream(InputProto proto) {

        if(proto.getEValue() == 0){//json
            if(proto.hasUtf8()){
                return OutputMarshaller.tagLengthDelimitedAsStream(DATA_2_UTF8_TAG,proto.getUtf8().getBytes(StandardCharsets.UTF_8));
            }
            return OutputMarshaller.tagLengthDelimitedAsStream(DATA_3_BYTES_TAG,proto.getBs().toByteArray());
        }
        var size = proto.getSerializedSize();
        var writer = new ProtoWriter(size);
        writer.writeUInt32(1,proto.getEValue());
        if(proto.hasUtf8()){
            writer.writeString(2,proto.getUtf8());
        }else {
            writer.writeBytes(3,proto.getBs().toByteArray());
        }

        log.debug("got proto use NONE json SerialEnum  {}  , size -> {}  ",proto.getE(),writer.buffer.length);
        return new ByteArrayInputStream(writer.buffer);
    }

    @SneakyThrows
    @Override
    public InputProto parse(InputStream inputStream) {
        return InputProto.parser().parseFrom(inputStream);
    }
}