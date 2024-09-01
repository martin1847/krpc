// Protocol Buffers - Google's data interchange format
// Copyright 2008 Google Inc.  All rights reserved.
//
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file or at
// https://developers.google.com/open-source/licenses/bsd
package tech.krpc.common.proto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * copy from com.google.protobuf.CodedInputStream.StreamDecoder
 * @author martin
 * @version 2023/12/20 16:34
 */
public class StreamDecoder extends MiniCodedInputStream {
    private final InputStream input;
    private final byte[]      buffer;
    /** bufferSize represents how many bytes are currently filled in the buffer */
    private       int         bufferSize;

    private int bufferSizeAfterLimit;
    private int pos;
    private int lastTag;

    /**
     * The total number of bytes read before the current buffer. The total bytes read up to the
     * current position can be computed as {@code totalBytesRetired + pos}. This value may be
     * negative if reading started in the middle of the current buffer (e.g. if the constructor that
     * takes a byte array and an offset was used).
     */
    private int totalBytesRetired;

    /** The absolute position of the end of the current message. */
    private int currentLimit = Integer.MAX_VALUE;

    //int recursionDepth;
    //
    //int recursionLimit = defaultRecursionLimit;

    /** Visible for subclasses. See setSizeLimit() */
    int sizeLimit = DEFAULT_SIZE_LIMIT;

    final        int available;
    //public StreamDecoder(final InputStream input){
    //    this(input,DEFAULT_BUFFER_SIZE);
    //}
    // about a int ,4 byte,
    static final int SMALL_TAGS_SWELL_BYTE = 6;

    public StreamDecoder(final InputStream input, int available) {
        this.input = input;
        this.buffer = new byte[Math.min(available + SMALL_TAGS_SWELL_BYTE, DEFAULT_BUFFER_SIZE)];
        this.bufferSize = 0;
        this.available = available;
        pos = 0;
        totalBytesRetired = 0;
    }

    /*
     * The following wrapper methods exist so that InvalidProtocolBufferExceptions thrown by the
     * InputStream can be differentiated from ones thrown by CodedInputStream itself. Each call to
     * an InputStream method that can throw IOException must be wrapped like this. We do this
     * because we sometimes need to modify IPBE instances after they are thrown far away from where
     * they are thrown (ex. to add unfinished messages) and we use this signal elsewhere in the
     * exception catch chain to know when to perform these operations directly or to wrap the
     * exception in their own IPBE so the extra information can be communicated without trampling
     * downstream information.
     */
    private static int read(InputStream input, byte[] data, int offset, int length)
            throws IOException {
        return input.read(data, offset, length);
    }

    private static long skip(InputStream input, long length) throws IOException {
        return input.skip(length);
    }

    //private static int available(InputStream input) throws IOException {
    //        return input.available();
    //}

    @Override
    public int readTag() throws IOException {
        if (isAtEnd()) {
            lastTag = 0;
            return 0;
        }

        lastTag = readRawVarint32();
        if (WireFormat.getTagFieldNumber(lastTag) == 0) {
            // If we actually read zero (or any tag number corresponding to field
            // number zero), that's not a valid tag.
            throw InvalidProtocolBufferException.invalidTag();
        }
        return lastTag;
    }

    //@Override
    //public void checkLastTagWas(final int value) throws InvalidProtocolBufferException {
    //    if (lastTag != value) {
    //        throw InvalidProtocolBufferException.invalidEndTag();
    //    }
    //}
    //
    //@Override
    //public int getLastTag() {
    //    return lastTag;
    //}
    //
    //@Override
    //public boolean skipField(final int tag) throws IOException {
    //    switch (WireFormat.getTagWireType(tag)) {
    //        case WireFormat.WIRETYPE_VARINT:
    //            skipRawVarint();
    //            return true;
    //        case WireFormat.WIRETYPE_FIXED64:
    //            skipRawBytes(FIXED64_SIZE);
    //            return true;
    //        case WireFormat.WIRETYPE_LENGTH_DELIMITED:
    //            skipRawBytes(readRawVarint32());
    //            return true;
    //        case WireFormat.WIRETYPE_START_GROUP:
    //            skipMessage();
    //            checkLastTagWas(
    //                    WireFormat.makeTag(WireFormat.getTagFieldNumber(tag), WireFormat.WIRETYPE_END_GROUP));
    //            return true;
    //        case WireFormat.WIRETYPE_END_GROUP:
    //            return false;
    //        case WireFormat.WIRETYPE_FIXED32:
    //            skipRawBytes(FIXED32_SIZE);
    //            return true;
    //        default:
    //            throw InvalidProtocolBufferException.invalidWireType();
    //    }
    //}
    //public void skipMessage() throws IOException {
    //    while (true) {
    //        final int tag = readTag();
    //        if (tag == 0 || !skipField(tag)) {
    //            return;
    //        }
    //    }
    //}

    @Override
    public String readStringRequireUtf8() throws IOException {
        final int size = readRawVarint32();
        final byte[] bytes;
        final int oldPos = pos;
        final int tempPos;
        if (size <= (bufferSize - oldPos) && size > 0) {
            // Fast path:  We already have the bytes in a contiguous buffer, so
            //   just copy directly from it.
            bytes = buffer;
            pos = oldPos + size;
            tempPos = oldPos;
        } else if (size == 0) {
            return "";
        } else if (size <= bufferSize) {
            refillBuffer(size);
            bytes = buffer;
            tempPos = 0;
            pos = tempPos + size;
        } else {
            // Slow path:  Build a byte array first then copy it.
            bytes = readRawBytesSlowPath(size, /* ensureNoLeakedReferences= */ false);
            tempPos = 0;
        }
        return new String(bytes, tempPos, size, StandardCharsets.UTF_8);
        //return Utf8.decodeUtf8(bytes, tempPos, size);
    }

    @Override
    public byte[] readByteArray() throws IOException {
        final int size = readRawVarint32();
        if (size <= (bufferSize - pos) && size > 0) {
            // Fast path: We already have the bytes in a contiguous buffer, so
            // just copy directly from it.
            final byte[] result = Arrays.copyOfRange(buffer, pos, pos + size);
            pos += size;
            return result;
        } else {
            // Slow path: Build a byte array first then copy it.
            // TODO: Do we want to protect from malicious input streams here?
            return readRawBytesSlowPath(size, /* ensureNoLeakedReferences= */ false);
        }
    }

    //@Override
    //public int readUInt32() throws IOException {
    //    return readRawVarint32();
    //}

    @Override
    public int readEnum() throws IOException {
        return readRawVarint32();
    }
    //
    //@Override
    //public int readSFixed32() throws IOException {
    //    return readRawLittleEndian32();
    //}
    //
    //@Override
    //public long readSFixed64() throws IOException {
    //    return readRawLittleEndian64();
    //}
    //
    //@Override
    //public int readSInt32() throws IOException {
    //    return decodeZigZag32(readRawVarint32());
    //}
    //
    //@Override
    //public long readSInt64() throws IOException {
    //    return decodeZigZag64(readRawVarint64());
    //}

    // =================================================================

    @Override
    public int readRawVarint32() throws IOException {
        // See implementation notes for readRawVarint64
        fastpath:
        {
            int tempPos = pos;

            if (bufferSize == tempPos) {
                break fastpath;
            }

            final byte[] buffer = this.buffer;
            int x;
            if ((x = buffer[tempPos++]) >= 0) {
                pos = tempPos;
                return x;
            } else if (bufferSize - tempPos < 9) {
                break fastpath;
            } else if ((x ^= (buffer[tempPos++] << 7)) < 0) {
                x ^= (~0 << 7);
            } else if ((x ^= (buffer[tempPos++] << 14)) >= 0) {
                x ^= (~0 << 7) ^ (~0 << 14);
            } else if ((x ^= (buffer[tempPos++] << 21)) < 0) {
                x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21);
            } else {
                int y = buffer[tempPos++];
                x ^= y << 28;
                x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21) ^ (~0 << 28);
                if (y < 0
                        && buffer[tempPos++] < 0
                        && buffer[tempPos++] < 0
                        && buffer[tempPos++] < 0
                        && buffer[tempPos++] < 0
                        && buffer[tempPos++] < 0) {
                    break fastpath; // Will throw malformedVarint()
                }
            }
            pos = tempPos;
            return x;
        }
        return (int) readRawVarint64SlowPath();
    }

    //private void skipRawVarint() throws IOException {
    //    if (bufferSize - pos >= MAX_VARINT_SIZE) {
    //        skipRawVarintFastPath();
    //    } else {
    //        skipRawVarintSlowPath();
    //    }
    //}
    //
    //private void skipRawVarintFastPath() throws IOException {
    //    for (int i = 0; i < MAX_VARINT_SIZE; i++) {
    //        if (buffer[pos++] >= 0) {
    //            return;
    //        }
    //    }
    //    throw InvalidProtocolBufferException.malformedVarint();
    //}
    //
    //private void skipRawVarintSlowPath() throws IOException {
    //    for (int i = 0; i < MAX_VARINT_SIZE; i++) {
    //        if (readRawByte() >= 0) {
    //            return;
    //        }
    //    }
    //    throw InvalidProtocolBufferException.malformedVarint();
    //}

    public long readRawVarint64() throws IOException {
        // Implementation notes:
        //
        // Optimized for one-byte values, expected to be common.
        // The particular code below was selected from various candidates
        // empirically, by winning VarintBenchmark.
        //
        // Sign extension of (signed) Java bytes is usually a nuisance, but
        // we exploit it here to more easily obtain the sign of bytes read.
        // Instead of cleaning up the sign extension bits by masking eagerly,
        // we delay until we find the final (positive) byte, when we clear all
        // accumulated bits with one xor.  We depend on javac to constant fold.
        fastpath:
        {
            int tempPos = pos;

            if (bufferSize == tempPos) {
                break fastpath;
            }

            final byte[] buffer = this.buffer;
            long x;
            int y;
            if ((y = buffer[tempPos++]) >= 0) {
                pos = tempPos;
                return y;
            } else if (bufferSize - tempPos < 9) {
                break fastpath;
            } else if ((y ^= (buffer[tempPos++] << 7)) < 0) {
                x = y ^ (~0 << 7);
            } else if ((y ^= (buffer[tempPos++] << 14)) >= 0) {
                x = y ^ ((~0 << 7) ^ (~0 << 14));
            } else if ((y ^= (buffer[tempPos++] << 21)) < 0) {
                x = y ^ ((~0 << 7) ^ (~0 << 14) ^ (~0 << 21));
            } else if ((x = y ^ ((long) buffer[tempPos++] << 28)) >= 0L) {
                x ^= (~0L << 7) ^ (~0L << 14) ^ (~0L << 21) ^ (~0L << 28);
            } else if ((x ^= ((long) buffer[tempPos++] << 35)) < 0L) {
                x ^= (~0L << 7) ^ (~0L << 14) ^ (~0L << 21) ^ (~0L << 28) ^ (~0L << 35);
            } else if ((x ^= ((long) buffer[tempPos++] << 42)) >= 0L) {
                x ^= (~0L << 7) ^ (~0L << 14) ^ (~0L << 21) ^ (~0L << 28) ^ (~0L << 35) ^ (~0L << 42);
            } else if ((x ^= ((long) buffer[tempPos++] << 49)) < 0L) {
                x ^=
                        (~0L << 7)
                                ^ (~0L << 14)
                                ^ (~0L << 21)
                                ^ (~0L << 28)
                                ^ (~0L << 35)
                                ^ (~0L << 42)
                                ^ (~0L << 49);
            } else {
                x ^= ((long) buffer[tempPos++] << 56);
                x ^=
                        (~0L << 7)
                                ^ (~0L << 14)
                                ^ (~0L << 21)
                                ^ (~0L << 28)
                                ^ (~0L << 35)
                                ^ (~0L << 42)
                                ^ (~0L << 49)
                                ^ (~0L << 56);
                if (x < 0L) {
                    if (buffer[tempPos++] < 0L) {
                        break fastpath; // Will throw malformedVarint()
                    }
                }
            }
            pos = tempPos;
            return x;
        }
        return readRawVarint64SlowPath();
    }

    long readRawVarint64SlowPath() throws IOException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            final byte b = readRawByte();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw InvalidProtocolBufferException.malformedVarint();
    }

    //public void resetSizeCounter() {
    //    totalBytesRetired = -pos;
    //}
    //
    //public int pushLimit(int byteLimit) throws InvalidProtocolBufferException {
    //    if (byteLimit < 0) {
    //        throw InvalidProtocolBufferException.negativeSize();
    //    }
    //    byteLimit += totalBytesRetired + pos;
    //    final int oldLimit = currentLimit;
    //    if (byteLimit > oldLimit) {
    //        throw InvalidProtocolBufferException.truncatedMessage();
    //    }
    //    currentLimit = byteLimit;
    //
    //    recomputeBufferSizeAfterLimit();
    //
    //    return oldLimit;
    //}

    private void recomputeBufferSizeAfterLimit() {
        bufferSize += bufferSizeAfterLimit;
        final int bufferEnd = totalBytesRetired + bufferSize;
        if (bufferEnd > currentLimit) {
            // Limit is in current buffer.
            bufferSizeAfterLimit = bufferEnd - currentLimit;
            bufferSize -= bufferSizeAfterLimit;
        } else {
            bufferSizeAfterLimit = 0;
        }
    }

    //public void popLimit(final int oldLimit) {
    //    currentLimit = oldLimit;
    //    recomputeBufferSizeAfterLimit();
    //}
    //
    //public int getBytesUntilLimit() {
    //    if (currentLimit == Integer.MAX_VALUE) {
    //        return -1;
    //    }
    //
    //    final int currentAbsolutePosition = totalBytesRetired + pos;
    //    return currentLimit - currentAbsolutePosition;
    //}

    public boolean isAtEnd() throws IOException {
        return pos == bufferSize && !tryRefillBuffer(1);
    }

    public int getTotalBytesRead() {
        return totalBytesRetired + pos;
    }

    //private interface RefillCallback {
    //    void onRefill();
    //}
    //
    //private CodedInputStream.StreamDecoder.RefillCallback refillCallback = null;

    /**
     * Reads more bytes from the input, making at least {@code n} bytes available in the buffer.
     * Caller must ensure that the requested space is not yet available, and that the requested
     * space is less than BUFFER_SIZE.
     *
     * @throws InvalidProtocolBufferException The end of the stream or the current limit was
     *     reached.
     */
    private void refillBuffer(int n) throws IOException {
        if (!tryRefillBuffer(n)) {
            // We have to distinguish the exception between sizeLimitExceeded and truncatedMessage. So
            // we just throw an sizeLimitExceeded exception here if it exceeds the sizeLimit
            if (n > sizeLimit - totalBytesRetired - pos) {
                throw InvalidProtocolBufferException.sizeLimitExceeded();
            } else {
                throw InvalidProtocolBufferException.truncatedMessage();
            }
        }
    }

    /**
     * Tries to read more bytes from the input, making at least {@code n} bytes available in the
     * buffer. Caller must ensure that the requested space is not yet available, and that the
     * requested space is less than BUFFER_SIZE.
     *
     * @return {@code true} If the bytes could be made available; {@code false} 1. Current at the
     *     end of the stream 2. The current limit was reached 3. The total size limit was reached
     */
    private boolean tryRefillBuffer(int n) throws IOException {
        if (pos + n <= bufferSize) {
            throw new IllegalStateException(
                    "refillBuffer() called when " + n + " bytes were already available in buffer");
        }

        // Check whether the size of total message needs to read is bigger than the size limit.
        // We shouldn't throw an exception here as isAtEnd() function needs to get this function's
        // return as the result.
        if (n > sizeLimit - totalBytesRetired - pos) {
            return false;
        }

        // Shouldn't throw the exception here either.
        if (totalBytesRetired + pos + n > currentLimit) {
            // Oops, we hit a limit.
            return false;
        }
        //
        //if (refillCallback != null) {
        //    refillCallback.onRefill();
        //}

        int tempPos = pos;
        if (tempPos > 0) {
            if (bufferSize > tempPos) {
                System.arraycopy(buffer, tempPos, buffer, 0, bufferSize - tempPos);
            }
            totalBytesRetired += tempPos;
            bufferSize -= tempPos;
            pos = 0;
        }

        // Here we should refill the buffer as many bytes as possible.
        int bytesRead =
                read(
                        input,
                        buffer,
                        bufferSize,
                        Math.min(
                                //  the size of allocated but unused bytes in the buffer
                                buffer.length - bufferSize,
                                //  do not exceed the total bytes limit
                                sizeLimit - totalBytesRetired - bufferSize));
        if (bytesRead == 0 || bytesRead < -1 || bytesRead > buffer.length) {
            throw new IllegalStateException(
                    input.getClass()
                            + "#read(byte[]) returned invalid result: "
                            + bytesRead
                            + "\nThe InputStream implementation is buggy.");
        }
        if (bytesRead > 0) {
            bufferSize += bytesRead;
            recomputeBufferSizeAfterLimit();
            return (bufferSize >= n) ? true : tryRefillBuffer(n);
        }

        return false;
    }

    public byte readRawByte() throws IOException {
        if (pos == bufferSize) {
            refillBuffer(1);
        }
        return buffer[pos++];
    }

    //public byte[] readRawBytes(final int size) throws IOException {
    //    final int tempPos = pos;
    //    if (size <= (bufferSize - tempPos) && size > 0) {
    //        pos = tempPos + size;
    //        return Arrays.copyOfRange(buffer, tempPos, tempPos + size);
    //    } else {
    //        // TODO: Do we want to protect from malicious input streams here?
    //        return readRawBytesSlowPath(size, /* ensureNoLeakedReferences= */ false);
    //    }
    //}

    /**
     * Exactly like readRawBytes, but caller must have already checked the fast path: (size <=
     * (bufferSize - pos) && size > 0)
     *
     * If ensureNoLeakedReferences is true, the value is guaranteed to have not escaped to
     * untrusted code.
     */
    private byte[] readRawBytesSlowPath(
            final int size, boolean ensureNoLeakedReferences) throws IOException {
        // Attempt to read the data in one byte array when it's safe to do.
        byte[] result = readRawBytesSlowPathOneChunk(size);
        if (result != null) {
            return ensureNoLeakedReferences ? result.clone() : result;
        }

        final int originalBufferPos = pos;
        final int bufferedBytes = bufferSize - pos;

        // Mark the current buffer consumed.
        totalBytesRetired += bufferSize;
        pos = 0;
        bufferSize = 0;

        // Determine the number of bytes we need to read from the input stream.
        int sizeLeft = size - bufferedBytes;

        // The size is very large. For security reasons we read them in small
        // chunks.
        List<byte[]> chunks = readRawBytesSlowPathRemainingChunks(sizeLeft);

        // OK, got everything.  Now concatenate it all into one buffer.
        final byte[] bytes = new byte[size];

        // Start by copying the leftover bytes from this.buffer.
        System.arraycopy(buffer, originalBufferPos, bytes, 0, bufferedBytes);

        // And now all the chunks.
        int tempPos = bufferedBytes;
        for (final byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, bytes, tempPos, chunk.length);
            tempPos += chunk.length;
        }

        // Done.
        return bytes;
    }

    /**
     * Attempts to read the data in one byte array when it's safe to do. Returns null if the size to
     * read is too large and needs to be allocated in smaller chunks for security reasons.
     *
     * <p>Returns a byte[] that may have escaped to user code via InputStream APIs.
     */
    private byte[] readRawBytesSlowPathOneChunk(final int size) throws IOException {
        if (size == 0) {
            return EMPTY_BYTE_ARRAY;
        }
        if (size < 0) {
            throw InvalidProtocolBufferException.negativeSize();
        }

        // Integer-overflow-conscious check that the message size so far has not exceeded sizeLimit.
        int currentMessageSize = totalBytesRetired + pos + size;
        if (currentMessageSize - sizeLimit > 0) {
            throw InvalidProtocolBufferException.sizeLimitExceeded();
        }

        // Verify that the message size so far has not exceeded currentLimit.
        if (currentMessageSize > currentLimit) {
            // Read to the end of the stream anyway.
            skipRawBytes(currentLimit - totalBytesRetired - pos);
            throw InvalidProtocolBufferException.truncatedMessage();
        }

        final int bufferedBytes = bufferSize - pos;
        // Determine the number of bytes we need to read from the input stream.
        int sizeLeft = size - bufferedBytes;
        // TODO: Consider using a value larger than DEFAULT_BUFFER_SIZE.
        if (sizeLeft < DEFAULT_BUFFER_SIZE || sizeLeft <= available) {
            // Either the bytes we need are known to be available, or the required buffer is
            // within an allowed threshold - go ahead and allocate the buffer now.
            final byte[] bytes = new byte[size];

            // Copy all of the buffered bytes to the result buffer.
            System.arraycopy(buffer, pos, bytes, 0, bufferedBytes);
            totalBytesRetired += bufferSize;
            pos = 0;
            bufferSize = 0;

            // Fill the remaining bytes from the input stream.
            int tempPos = bufferedBytes;
            while (tempPos < bytes.length) {
                int n = read(input, bytes, tempPos, size - tempPos);
                if (n == -1) {
                    throw InvalidProtocolBufferException.truncatedMessage();
                }
                totalBytesRetired += n;
                tempPos += n;
            }

            return bytes;
        }

        return null;
    }

    /**
     * Reads the remaining data in small chunks from the input stream.
     *
     * Returns a byte[] that may have escaped to user code via InputStream APIs.
     */
    private List<byte[]> readRawBytesSlowPathRemainingChunks(int sizeLeft) throws IOException {
        // The size is very large.  For security reasons, we can't allocate the
        // entire byte array yet.  The size comes directly from the input, so a
        // maliciously-crafted message could provide a bogus very large size in
        // order to trick the app into allocating a lot of memory.  We avoid this
        // by allocating and reading only a small chunk at a time, so that the
        // malicious message must actually *be* extremely large to cause
        // problems.  Meanwhile, we limit the allowed size of a message elsewhere.
        final List<byte[]> chunks = new ArrayList<>();

        while (sizeLeft > 0) {
            // TODO: Consider using a value larger than DEFAULT_BUFFER_SIZE.
            final byte[] chunk = new byte[Math.min(sizeLeft, DEFAULT_BUFFER_SIZE)];
            int tempPos = 0;
            while (tempPos < chunk.length) {
                final int n = input.read(chunk, tempPos, chunk.length - tempPos);
                if (n == -1) {
                    throw InvalidProtocolBufferException.truncatedMessage();
                }
                totalBytesRetired += n;
                tempPos += n;
            }
            sizeLeft -= chunk.length;
            chunks.add(chunk);
        }

        return chunks;
    }

    ///**
    // * Like readBytes, but caller must have already checked the fast path: (size <= (bufferSize -
    // * pos) && size > 0 || size == 0)
    // */
    //private ByteString readBytesSlowPath(final int size) throws IOException {
    //    final byte[] result = readRawBytesSlowPathOneChunk(size);
    //    if (result != null) {
    //        // We must copy as the byte array was handed off to the InputStream and a malicious
    //        // implementation could retain a reference.
    //        return ByteString.copyFrom(result);
    //    }
    //
    //    final int originalBufferPos = pos;
    //    final int bufferedBytes = bufferSize - pos;
    //
    //    // Mark the current buffer consumed.
    //    totalBytesRetired += bufferSize;
    //    pos = 0;
    //    bufferSize = 0;
    //
    //    // Determine the number of bytes we need to read from the input stream.
    //    int sizeLeft = size - bufferedBytes;
    //
    //    // The size is very large. For security reasons we read them in small
    //    // chunks.
    //    List<byte[]> chunks = readRawBytesSlowPathRemainingChunks(sizeLeft);
    //
    //    // OK, got everything.  Now concatenate it all into one buffer.
    //    final byte[] bytes = new byte[size];
    //
    //    // Start by copying the leftover bytes from this.buffer.
    //    System.arraycopy(buffer, originalBufferPos, bytes, 0, bufferedBytes);
    //
    //    // And now all the chunks.
    //    int tempPos = bufferedBytes;
    //    for (final byte[] chunk : chunks) {
    //        System.arraycopy(chunk, 0, bytes, tempPos, chunk.length);
    //        tempPos += chunk.length;
    //    }
    //
    //    return ByteString.wrap(bytes);
    //}
    //
    //@Override
    public void skipRawBytes(final int size) throws IOException {
        if (size <= (bufferSize - pos) && size >= 0) {
            // We have all the bytes we need already.
            pos += size;
        } else {
            skipRawBytesSlowPath(size);
        }
    }

    /**
     * Exactly like skipRawBytes, but caller must have already checked the fast path: (size <=
     * (bufferSize - pos) && size >= 0)
     */
    private void skipRawBytesSlowPath(final int size) throws IOException {
        if (size < 0) {
            throw InvalidProtocolBufferException.negativeSize();
        }

        if (totalBytesRetired + pos + size > currentLimit) {
            // Read to the end of the stream anyway.
            skipRawBytes(currentLimit - totalBytesRetired - pos);
            // Then fail.
            throw InvalidProtocolBufferException.truncatedMessage();
        }

        int totalSkipped = 0;
        //if (refillCallback == null) {
        // Skipping more bytes than are in the buffer.  First skip what we have.
        totalBytesRetired += pos;
        totalSkipped = bufferSize - pos;
        bufferSize = 0;
        pos = 0;

        try {
            while (totalSkipped < size) {
                int toSkip = size - totalSkipped;
                long skipped = skip(input, toSkip);
                if (skipped < 0 || skipped > toSkip) {
                    throw new IllegalStateException(
                            input.getClass()
                                    + "#skip returned invalid result: "
                                    + skipped
                                    + "\nThe InputStream implementation is buggy.");
                } else if (skipped == 0) {
                    // The API contract of skip() permits an inputstream to skip zero bytes for any reason
                    // it wants. In particular, ByteArrayInputStream will just return zero over and over
                    // when it's at the end of its input. In order to actually confirm that we've hit the
                    // end of input, we need to issue a read call via the other path.
                    break;
                }
                totalSkipped += (int) skipped;
            }
        } finally {
            totalBytesRetired += totalSkipped;
            recomputeBufferSizeAfterLimit();
        }
        //}
        if (totalSkipped < size) {
            // Skipping more bytes than are in the buffer.  First skip what we have.
            int tempPos = bufferSize - pos;
            pos = bufferSize;

            // Keep refilling the buffer until we get to the point we wanted to skip to.
            // This has the side effect of ensuring the limits are updated correctly.
            refillBuffer(1);
            while (size - tempPos > bufferSize) {
                tempPos += bufferSize;
                pos = bufferSize;
                refillBuffer(1);
            }

            pos = size - tempPos;
        }
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}