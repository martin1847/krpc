package tech.krpc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * HARDEN-B3 fix #6 — the hand-rolled {@link InputProto#InputProto(InputStream)} decoder.
 *
 * <p><b>Placement matters (SHADOW-CLASS TRAP):</b> {@code rpc-common}'s OWN test tree carries a
 * protobuf-generated {@code InputProto} that shadows the hand-rolled main class, hiding this decoder.
 * These tests live in the <b>rpc-server</b> test tree, which sees the rpc-common MAIN jar — so
 * {@code new InputProto(in)} here is the real hand-rolled decoder under test.
 *
 * <p>Wire format (protobuf): a tag byte = {@code (fieldNumber << 3) | wireType}. The decoder handles
 * tag {@code 8} (field 1, VARINT — enum {@code e}), tag {@code 18} (field 2, LENGTH_DELIMITED —
 * string {@code utf8}), tag {@code 26} (field 3, LENGTH_DELIMITED — bytes {@code bs}).
 */
class InputProtoBoundaryTest {

    // ---- tiny protobuf wire encoder -------------------------------------------------------------

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeLenDelimited(ByteArrayOutputStream out, int tag, byte[] payload) {
        out.write(tag);                     // small field numbers ⇒ single tag byte
        writeVarint(out, payload.length);
        out.write(payload, 0, payload.length);
    }

    private static void writeString(ByteArrayOutputStream out, int tag, String s) {
        writeLenDelimited(out, tag, s.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeVarintField(ByteArrayOutputStream out, int tag, int value) {
        out.write(tag);
        writeVarint(out, value);
    }

    @Test
    void roundTrip_enumAndString() throws Exception {
        var buf = new ByteArrayOutputStream();
        writeVarintField(buf, 8, 2);   // field 1 (enum e) = 2
        writeString(buf, 18, "hi");    // field 2 (utf8) = "hi"

        var msg = new InputProto(new ByteArrayInputStream(buf.toByteArray()));
        assertEquals("hi", msg.getUtf8());
        assertEquals(2, msg.getEValue());
    }

    @Test
    void availableZero_butStreamHasData_stillDecodes() throws Exception {
        // THE #6 core bug: available() is a best-effort hint that can legitimately return 0 for a
        // stream that still has data. Pre-fix the ctor treated available()==0 as EOF and returned an
        // EMPTY message. Wrap the real bytes in a stream whose available() ALWAYS returns 0.
        var buf = new ByteArrayOutputStream();
        writeString(buf, 18, "hi");

        InputStream zeroAvailable = new FilterInputStream(
                new ByteArrayInputStream(buf.toByteArray())) {
            @Override
            public int available() {
                return 0;
            }
        };

        var msg = new InputProto(zeroAvailable);
        assertEquals("hi", msg.getUtf8());
    }

    @Test
    void unknownTag_isSkipped_notTruncated() throws Exception {
        // Put an UNKNOWN field FIRST, the known utf8 field AFTER. Pre-fix `default: done=true`
        // truncated everything past the unknown field, dropping "keep".
        var buf = new ByteArrayOutputStream();
        writeVarintField(buf, 40, 7);   // unknown field 5, wiretype 0 (VARINT) = 7
        writeString(buf, 18, "keep");   // known field 2 (utf8) = "keep"

        var msg = new InputProto(new ByteArrayInputStream(buf.toByteArray()));
        assertEquals("keep", msg.getUtf8());
    }

    @Test
    void invalidWireType_throws_notSilentTruncation() {
        // field 5, wiretype 7 (undefined): (5<<3)|7 == 47. skipField throws InvalidWireTypeException,
        // wrapped as RuntimeException by the ctor's IOException catch — malformed input surfaces as an
        // error, never a quiet truncation.
        byte[] bytes = {47, 1};
        assertThrows(RuntimeException.class,
                () -> new InputProto(new ByteArrayInputStream(bytes)));
    }

    @Test
    void emptyStream_yieldsDefaults_noThrow() throws Exception {
        var msg = new InputProto(new ByteArrayInputStream(new byte[0]));
        assertEquals("", msg.getUtf8());
        assertEquals(0, msg.getEValue());
    }
}
