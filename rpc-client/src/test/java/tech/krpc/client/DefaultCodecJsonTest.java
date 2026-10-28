package tech.krpc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import tech.krpc.common.proto.InputMarshaller;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.SerialEnum;
import tech.krpc.serial.Serial;
import org.junit.jupiter.api.Test;

/**
 * NS-4 — JSON is the default wire codec, exercised on the ACTUAL decode/dispatch path.
 *
 * <p>The umbrella NORTH_STAR NS-4 makes JSON the default serialization (SPEC §2). The wire
 * envelope's codec is {@code InputProto} field {@code e} (proto3 enum, field number 1): an
 * inbound request that does not set it decodes to {@code getEValue()==0}. Production server
 * dispatch resolves the codec by exactly {@code Serial.Instance.get(arg.getEValue())} —
 * see {@code rpc-server/.../UnaryMethod.java:183} and
 * {@code rpc-server/.../invoke/DynamicInvoke.java:15} — and {@code InputMarshaller.stream}
 * treats {@code getEValue()==0} as JSON ({@code rpc-common/.../common/proto/InputMarshaller.java:33}).
 *
 * <p>This test drives the REAL classes production runs: it parses a DEFAULT (empty / codec
 * unset) wire envelope through {@link InputMarshaller#parse} and passes the decoded codec
 * value through the SAME {@code Serial.Instance.get(...)} resolver, asserting JSON. So an
 * envelope/generator change that makes an unset input select a non-JSON codec fails here,
 * not just a change to the {@code SerialEnum} constant. (Source of truth is the generated
 * {@link InputProto#getEValue()} and the dispatch resolver, NOT {@code proto/internal.proto}
 * whose {@code e} field is commented out.)
 *
 * <p>Plain JUnit test (owning module: rpc-client, the client registration layer, which also
 * verifies the client registration default). NS-4 is a value / wire-path contract, not a
 * bytecode dependency, so it is not an ArchUnit frozen rule.
 */
class DefaultCodecJsonTest {

    @Test
    void defaultWireEnvelopeDecodesToJsonCodec() throws Exception {
        // A DEFAULT inbound envelope = no bytes set (codec field e unset). Parse it exactly as
        // gRPC server dispatch does (InputMarshaller.parse -> new InputProto(InputStream)).
        InputProto defaultInput =
            new InputMarshaller().parse(new ByteArrayInputStream(new byte[0]));

        // proto3 default: an unset codec field is numeric 0 on the wire.
        assertEquals(0, defaultInput.getEValue(),
            "an unset codec field on the wire must decode to 0 (proto3 default)");

        // The EXACT resolver server dispatch uses (Serial.Instance.get(arg.getEValue()))
        // must map that default to the JSON serial.
        assertSame(SerialEnum.JSON, Serial.Instance.get(defaultInput.getEValue()).id(),
            "the default wire codec (unset e -> 0) must resolve to JSON on the dispatch path");
    }

    @Test
    void serialEnumJsonIsOrdinalZero() {
        // Pin the invariant the decode path relies on: JSON is the ordinal-0 codec, so the
        // proto3 default and the resolver key line up.
        assertEquals(0, SerialEnum.JSON_VALUE, "JSON must be the ordinal-0 (proto3 default) codec");
        assertSame(SerialEnum.JSON, Serial.Instance.get(SerialEnum.JSON_VALUE).id(),
            "codec resolver for SerialEnum.JSON must be the JSON serial");
    }

    @Test
    void clientRegistrationDefaultIsJson() throws ReflectiveOperationException {
        // RpcClientFactory.globalSerialEnum is the default codec a client picks when none is
        // set (getDefaultSerial() falls back to it). It must be JSON. Read reflectively: the
        // field is private with no public getter, and this asserts the durable default.
        Field global = RpcClientFactory.class.getDeclaredField("globalSerialEnum");
        global.setAccessible(true);
        assertSame(SerialEnum.JSON, global.get(null),
            "RpcClientFactory.globalSerialEnum (client registration default codec) must be JSON");
    }
}
