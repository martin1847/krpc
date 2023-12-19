/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package com.bt.rpc.common.proto;

import java.nio.charset.StandardCharsets;

import com.google.protobuf.CodedOutputStream;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author martin
 * @version 2023/12/18 14:49
 */
@Slf4j
public class ProtoWriter {// implements  IProtoWriter

    /**
     * Used for string, bytes, embedded messages, packed repeated fields
     *
     * Only repeated numeric types (types which use the varint, 32-bit,
     * or 64-bit wire types) can be packed. In proto3, such fields are
     * packed by default.
     */
    static final int LENGTH_DELIMITED_WIRE_TYPE = 2;

    /**
     * Used for int32, int64, uint32, uint64, sint32, sint64, bool, enum
     */
    static final int VARINT_WIRE_TYPE = 0;

    static int makeTag(final int fieldNumber, final int wireType) {
        return fieldNumber << 3 | wireType;
    }

    final byte[] buffer;

    private int position;

    public ProtoWriter(int size) {this.buffer = new byte[size];}

    void writeUInt32NoTag(int value) {
        this.position += writeUInt32(this.buffer,this.position,value);
        //while ((value & -128) != 0) {
        //    this.buffer[this.position++] = (byte) (value & 127 | 128);
        //    value >>>= 7;
        //}
        //this.buffer[this.position++] = (byte) value;
    }

    /**
     * WireFormat.makeTag
     */
    void writeTag(final int fieldNumber, final int wireType) {
        this.writeUInt32NoTag(makeTag(fieldNumber, wireType));
    }

    public void writeUInt32(final int fieldNumber, final int value) {
        this.writeTag(fieldNumber, VARINT_WIRE_TYPE);
        this.writeUInt32NoTag(value);
    }

    public void writeString(int fieldNumber, String value) {
        var bytes =  value.getBytes(StandardCharsets.UTF_8);
        writeBytes(fieldNumber,bytes);
    }

    public void writeBytes(int fieldNumber, byte[] bytes) {
        //var bytes =  value.getBytes(StandardCharsets.UTF_8);
        this.writeTag(fieldNumber, LENGTH_DELIMITED_WIRE_TYPE);
        //var bytes = value.getBytes(StandardCharsets.UTF_8);
        this.writeUInt32NoTag(bytes.length);
        System.arraycopy(bytes, 0, buffer, position, bytes.length);
        position += bytes.length;
    }

    static int writeUInt32(byte[] buf,int offset,int value){
        var begin = offset;
        while ((value & -128) != 0) {
            buf[offset++] = (byte) (value & 127 | 128);
            value >>>= 7;
        }
        buf[offset++] = (byte) value;
        return offset - begin;
    }

    static byte[] streamTag(final int fieldNumber, final int wireType){
        var tag =  makeTag(fieldNumber,wireType);
        return streamUInt32(tag);
    }

    static byte[] streamUInt32(int value){
        var size = CodedOutputStream.computeUInt32SizeNoTag(value);
        var buf = new byte[size];
        writeUInt32(buf,0,value);
        return buf;
    }
}