package tech.krpc.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.grpc.Metadata;
import tech.krpc.util.FlagResolution.Source;

/**
 * OTEL-001 (ADR-0006) + ADR-0003 (umbrella) known debt 1: unit contract for {@link KrpcOtel} — the
 * kill-switch resolver and the gRPC metadata setter's single-value guarantee (the mechanism that
 * keeps exactly one {@code traceparent} on the wire when OTel injection supersedes the ADR-0003
 * MDC-forwarded value).
 *
 * <p><b>Behaviour change pinned here (approved in ADR-0003 known debt 1).</b> The old resolver
 * treated <em>any</em> value other than {@code false}/{@code 0} as ON, so a typo'd kill switch
 * ({@code KRPC_OTEL=fasle}, {@code KRPC_OTEL=yes}) silently left telemetry ON — exactly the case the
 * switch exists for. Unrecognised values and read failures now resolve to the safe side, OFF.
 */
class KrpcOtelTest {

    @Test
    void resolve_defaultsOnWhenNotConfigured() {
        for (String raw : new String[] {null, "", "  ", "\t\n"}) {
            assertTrue(KrpcOtel.resolve(raw, raw).value(), "unconfigured must default ON");
            assertEquals(Source.DEFAULT, KrpcOtel.resolve(raw, raw).source());
        }
    }

    @Test
    void resolve_explicitFalseOrZeroDisables() {
        assertFalse(KrpcOtel.resolve("false", null).value());
        assertFalse(KrpcOtel.resolve("FALSE", null).value());
        assertFalse(KrpcOtel.resolve("0", null).value());
        assertFalse(KrpcOtel.resolve(null, "false").value());
        assertFalse(KrpcOtel.resolve(null, "0").value());
        // Requirement 1: trimmed on both sides — " false " is a pressed switch, not a typo.
        assertFalse(KrpcOtel.resolve(" false ", null).value());
        assertFalse(KrpcOtel.resolve(null, "\t0\n").value());
    }

    @Test
    void resolve_recognisedTruthyValuesEnable_evenWithSurroundingWhitespace() {
        assertTrue(KrpcOtel.resolve("true", null).value());
        assertTrue(KrpcOtel.resolve(null, "1").value());
        assertTrue(KrpcOtel.resolve(" TRUE ", null).value());
        assertTrue(KrpcOtel.resolve(null, " 1 ").value());
    }

    /** ADR-0003 requirement 2: the kill switch must stay pressable, so garbage means OFF. */
    @Test
    void resolve_unrecognisedValueDisables_soATypoCannotWeldTheSwitchOn() {
        for (String raw : new String[] {"yes", "fasle", "no", "on", "enabled", "2"}) {
            assertFalse(KrpcOtel.resolve(raw, null).value(),
                    () -> "property \"" + raw + "\" must resolve OFF (safe side), not ON");
            assertEquals(Source.UNRECOGNIZED, KrpcOtel.resolve(raw, null).source());
            assertFalse(KrpcOtel.resolve(null, raw).value(),
                    () -> "env \"" + raw + "\" must resolve OFF (safe side), not ON");
        }
    }

    @Test
    void resolve_systemPropertyWinsOverEnv() {
        assertFalse(KrpcOtel.resolve("false", "true").value(), "prop false must win");
        assertTrue(KrpcOtel.resolve("true", "false").value(), "prop true must win");
        assertFalse(KrpcOtel.resolve("  ", "false").value(),
                "a blank property is not configuration and must not shadow the env var");
    }

    /**
     * ADR-0003 requirement 2: the lookup itself is guarded. Driven with a property name the JDK
     * rejects ({@code System.getProperty("")} throws {@link IllegalArgumentException}), which
     * exercises the real {@code catch} in {@link KrpcOtel#read} — no test seam involved.
     */
    @Test
    void read_lookupFailureFallsToTheSafeSideInsteadOfPropagating() {
        var resolution = KrpcOtel.read("", "KRPC_OTEL_ABSENT_FOR_TEST");
        assertFalse(resolution.value(), "a failed read must leave the kill switch pressable");
        assertEquals(Source.READ_FAILURE, resolution.source());
        assertEquals("read-failure(IllegalArgumentException)", resolution.describe());
    }

    /** The accessor is the single resolution point, and an unconfigured runtime observes ON. */
    @Test
    void enabled_defaultsOnInAnUnconfiguredRuntime() {
        assumeTrue(System.getProperty(KrpcOtel.PROPERTY_ENABLED) == null
                        && System.getenv(KrpcOtel.ENV_ENABLED) == null,
                "the OTEL kill switch is configured in this environment; the default check is moot");
        assertTrue(KrpcOtel.enabled(), "telemetry is present unless the switch is pressed");
        assertTrue(KrpcOtel.enabled(), "the accessor is stable across calls (memoised, not re-read)");
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
