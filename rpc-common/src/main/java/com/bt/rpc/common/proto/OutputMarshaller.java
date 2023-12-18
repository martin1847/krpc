/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package com.bt.rpc.common.proto;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.bt.rpc.internal.OutputProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import io.grpc.MethodDescriptor.Marshaller;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author martin
 * @version 2023/12/18 11:44
 */
@Slf4j
public class OutputMarshaller implements Marshaller<OutputProto> {

    static final int DATA_3_UTF8_TAG = ProtoWriter.makeTag(3,ProtoWriter.LENGTH_DELIMITED_WIRE_TYPE);

    static final int DATA_3_UTF8_TAG_SIZE = CodedOutputStream.computeUInt32SizeNoTag(DATA_3_UTF8_TAG);
    static final int DATA_4_BYTES_TAG = ProtoWriter.makeTag(4,ProtoWriter.LENGTH_DELIMITED_WIRE_TYPE);

    static final int DATA_4_BYTES_TAG_SIZE = CodedOutputStream.computeUInt32SizeNoTag(DATA_4_BYTES_TAG);

    @Override
    public InputStream stream(OutputProto proto) {

        if (proto.hasUtf8()) { // most success
            return data3(proto.getUtf8());
        }

        if (proto.getC() > 0) { // error
            var size = proto.getSerializedSize();
            var writer = new ProtoWriter(size);
            writer.writeUInt32(1, proto.getC());
            writer.writeString(2, proto.getM());
            var buf = writer.buffer;
            log.debug("error proto code {} , size {}", proto.getC(), size);
            return new ByteArrayInputStream(buf);
        }

        return data4bytes(proto.getBs());

        //if (!proto.getMBytes().isEmpty()) {
        //    com.google.protobuf.GeneratedMessageV3.writeString(output, 2, m_);
        //}
        //if (dataCase_ == 3) {
        //    com.google.protobuf.GeneratedMessageV3.writeString(output, 3, data_);
        //}
        //if (dataCase_ == 4) {
        //    output.writeBytes(
        //            4, (com.google.protobuf.ByteString) data_);
        //}
        //


        //var buf = proto.toByteArray();
        //log.debug("got proto {} / {} ",buf.length,proto);
        ////var out = CodedOutputStream.newInstance(buf);
        ////proto.writeTo(out);
        //
        //var buf2 = new byte[proto.getSerializedSize()];
        //var out = CodedOutputStream.newInstance(buf2);
        ////out.writeInt32(1,0);
        //out.writeString(3, proto.getUtf8());
        //out.flush();
        //

    }

    @SneakyThrows
    @Override
    public OutputProto parse(InputStream inputStream) {
        return OutputProto.parser().parseFrom(inputStream);
    }
    //
    //public static void main(String[] args) {
    //    System.out.println(DATA_3_UTF8_TAG + " ->> " + CodedOutputStream.computeUInt32SizeNoTag(DATA_3_UTF8_TAG));
    //    System.out.println(DATA_4_BYTES_TAG + " ->> " + CodedOutputStream.computeUInt32SizeNoTag(DATA_4_BYTES_TAG));
    //}

    static InputStream data3(String utf8){
        return dataAsStream(DATA_3_UTF8_TAG,DATA_3_UTF8_TAG_SIZE,utf8.getBytes(StandardCharsets.UTF_8));
    }

    static InputStream data4bytes(ByteString bytes){
        return dataAsStream(DATA_4_BYTES_TAG,DATA_4_BYTES_TAG_SIZE,bytes.toByteArray());
    }

    public static InputStream dataAsStream(int tag, int tagSize, byte[] bytes) {
        //var bytes = utf8.getBytes(StandardCharsets.UTF_8);
        var writer = new ProtoWriter(tagSize + CodedOutputStream.computeUInt32SizeNoTag(bytes.length));
        writer.writeUInt32NoTag(tag);
        writer.writeUInt32NoTag(bytes.length);
        //var tag = Unpooled.wrappedBuffer(writer.buffer);
        //var value = Unpooled.wrappedBuffer(bytes);

        return new ByteBufInputStream(Unpooled.wrappedBuffer(writer.buffer, bytes));
    }




}