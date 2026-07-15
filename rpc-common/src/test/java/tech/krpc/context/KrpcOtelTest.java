package tech.krpc.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.grpc.Metadata;

/**
 * OTEL-001 (ADR-0006): unit contract for {@link KrpcOtel} — the kill-switch resolver and the gRPC
 * metadata setter's single-value guarantee (the mechanism that keeps exactly one {@code traceparent}
 * on the wire when OTel injection supersedes the ADR-0003 MDC-forwarded value).
 */
class KrpcOtelTest {

    @Test
    void resolveEnabled_defaultsOnWhenUnset() {
        assertTrue(KrpcOtel.resolveEnabled(null, null), "unset must default ON");
        assertTrue(KrpcOtel.resolveEnabled("  ", null), "blank prop must default ON");
        assertTrue(KrpcOtel.resolveEnabled(null, ""), "blank env must default ON");
    }

    @Test
    void resolveEnabled_explicitFalseOrZeroDisables() {
        assertFalse(KrpcOtel.resolveEnabled("false", null));
        assertFalse(KrpcOtel.resolveEnabled("FALSE", null));
        assertFalse(KrpcOtel.resolveEnabled("0", null));
        assertFalse(KrpcOtel.resolveEnabled(null, "false"));
        assertFalse(KrpcOtel.resolveEnabled(null, "0"));
    }

    @Test
    void resolveEnabled_anythingElseStaysOn() {
        assertTrue(KrpcOtel.resolveEnabled("true", null));
        assertTrue(KrpcOtel.resolveEnabled(null, "1"));
        assertTrue(KrpcOtel.resolveEnabled("yes", null));
    }

    @Test
    void resolveEnabled_systemPropertyWinsOverEnv() {
        assertFalse(KrpcOtel.resolveEnabled("false", "true"), "prop false must win");
        assertTrue(KrpcOtel.resolveEnabled("true", "false"), "prop true must win");
    }

    @Test
    void metadataSetter_overwritesToSingleValue() {
        Metadata md = new Metadata();
        // Simulate an already-present (MDC-forwarded) traceparent, then an OTel injection.
        KrpcOtel.METADATA_SETTER.set(md, "traceparent", "00-aaaa-bbbb-01");
        KrpcOtel.METADATA_SETTER.set(md, "traceparent", "00-cccc-dddd-01");

        List<String> values = new ArrayList<>();
        var all = md.getAll(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER));
        if (all != null) {
            all.forEach(values::add);
        }
        assertEquals(List.of("00-cccc-dddd-01"), values,
                "the setter must overwrite, leaving exactly one value (single traceparent on the wire)");
    }

    @Test
    void metadataGetter_readsHeader() {
        Metadata md = new Metadata();
        md.put(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER), "00-eeee-ffff-01");
        assertEquals("00-eeee-ffff-01", KrpcOtel.METADATA_GETTER.get(md, "traceparent"));
    }
}
