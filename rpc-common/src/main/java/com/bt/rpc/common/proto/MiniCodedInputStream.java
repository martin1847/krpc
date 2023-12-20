/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package com.bt.rpc.common.proto;

import java.io.Closeable;
import java.io.IOException;


/**
 *
 * @author martin
 * @version 2023/12/20 16:39
 */
public abstract class MiniCodedInputStream implements Closeable {

    public static final int DEFAULT_BUFFER_SIZE = 4096;
    // Integer.MAX_VALUE == 0x7FFFFFF == INT_MAX from limits.h
    static final int DEFAULT_SIZE_LIMIT = Integer.MAX_VALUE;
    static volatile int defaultRecursionLimit = 100;

    public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];


    /**
     * Attempt to read a field tag, returning zero if we have reached EOF. Protocol message parsers
     * use this to read tags, since a protocol message may legally end wherever a tag occurs, and zero
     * is not a valid tag number.
     */
    public abstract int readTag() throws IOException;

    public abstract int readRawVarint32() throws IOException;

    /**
     * Read a {@code string} field value from the stream. If the stream contains malformed UTF-8,
     * throw exception {@link InvalidProtocolBufferException}.
     */
    public abstract String readStringRequireUtf8() throws IOException;

    public abstract byte[] readByteArray() throws IOException;

    public byte[] readBytes() throws IOException{
        return readByteArray();
    }

    public int readInt32() throws IOException{
        return readRawVarint32();
    }
    /**
     * Read an enum field value from the stream. Caller is responsible for converting the numeric
     * value to an actual enum.
     */
    public int readEnum() throws IOException{
        return readRawVarint32();
    }

}