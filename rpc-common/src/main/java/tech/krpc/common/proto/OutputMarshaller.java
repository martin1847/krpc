/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package tech.krpc.common.proto;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import tech.krpc.internal.OutputProto;
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

    static final byte[] DATA_3_UTF8_TAG = ProtoWriter.streamTag(3,ProtoWriter.LENGTH_DELIMITED_WIRE_TYPE);
    static final byte[] DATA_4_BYTES_TAG = ProtoWriter.streamTag(4,ProtoWriter.LENGTH_DELIMITED_WIRE_TYPE);

    static final byte[] CODE_1_TAG = ProtoWriter.streamTag(1,ProtoWriter.VARINT_WIRE_TYPE);
    static final byte[] MSG_2_TAG = ProtoWriter.streamTag(2,ProtoWriter.LENGTH_DELIMITED_WIRE_TYPE);


    @Override
    public InputStream stream(OutputProto proto) {

        if (proto.hasUtf8()) { // most success
            return tagLengthDelimitedAsStream(DATA_3_UTF8_TAG,proto.getUtf8().getBytes(StandardCharsets.UTF_8));
        }

        if (proto.getC() > 0) { // error
            //var size = proto.getSerializedSize();
            var msg = proto.getM();
            var code1bytes = ProtoWriter.streamUInt32(proto.getC());

            var msgBytes = msg.getBytes(StandardCharsets.UTF_8);
            var msgLenBytes = ProtoWriter.streamUInt32(msgBytes.length);
            //
            //var size = CodedOutputStream.computeInt32Size(1, proto.getC())
            //        + CodedOutputStream.computeStringSize(2, msg);
            //var writer = new ProtoWriter(size);
            //writer.writeUInt32(1, proto.getC());
            //writer.writeString(2, msg);
            //var buf = writer.buffer;
            var buf = Unpooled.wrappedBuffer(CODE_1_TAG,code1bytes,MSG_2_TAG,msgLenBytes,msgBytes);
            log.debug("error proto code {} , msgBytes {}", proto.getC(), msgBytes.length);
            return new ByteBufInputStream(buf);

            //return new ByteArrayInputStream(buf);
        }

        return tagLengthDelimitedAsStream(DATA_4_BYTES_TAG,proto.getBs());

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
        return new OutputProto(inputStream);
    }
    //
    //public static void main(String[] args) {
    //    System.out.println(DATA_3_UTF8_TAG + " ->> " + CodedOutputStream.computeUInt32SizeNoTag(DATA_3_UTF8_TAG));
    //    System.out.println(DATA_4_BYTES_TAG + " ->> " + CodedOutputStream.computeUInt32SizeNoTag(DATA_4_BYTES_TAG));
    //}

    //static InputStream data3(String utf8){
    //    return dataAsStream(DATA_3_UTF8_TAG,DATA_3_UTF8_TAG_SIZE,utf8.getBytes(StandardCharsets.UTF_8));
    //}
    //
    //static InputStream data4bytes(ByteString bytes){
    //    return dataAsStream(DATA_4_BYTES_TAG,DATA_4_BYTES_TAG_SIZE,bytes.toByteArray());
    //}

    public static InputStream tagLengthDelimitedAsStream(byte[] tag, byte[] bytes) {
        //var bytes = utf8.getBytes(StandardCharsets.UTF_8);
        //var writer = new ProtoWriter(tagSize + CodedOutputStream.computeUInt32SizeNoTag(bytes.length));
        //writer.writeUInt32NoTag(tag);
        //writer.writeUInt32NoTag(bytes.length);
        //var tag = Unpooled.wrappedBuffer(writer.buffer);
        //var value = Unpooled.wrappedBuffer(bytes);
        //log.debug("use tag {}", Arrays.toString(tag));

        return new ByteBufInputStream(Unpooled.wrappedBuffer(tag,ProtoWriter.streamUInt32(bytes.length), bytes));
    }




}