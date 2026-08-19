package tech.krpc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tech.krpc.util.FlagResolution.Source;

/**
 * ADR-0003 (umbrella) requirements 1 and 2 — the value semantics every hand-written krpc flag
 * inherits. This is the promised surface of the shared resolver: the matrix below is what "trimmed,
 * case-insensitive, property wins, unrecognised falls to the safe side" actually means.
 *
 * <p>Both polarities of {@code safeValue} and {@code defaultValue} are exercised, so the parser is
 * pinned as parameterised rather than as "always false" — a flag class that passes the wrong side
 * (or a parser that hardcodes one) goes RED here and in its own contract test.
 */
class FlagResolutionTest {

    /** ON side of requirement 1, including the whitespace that used to silently mean OFF. */
    @Test
    void recognisedTruthyValuesAreOn_trimmedAndCaseInsensitive() {
        for (String raw : new String[] {"true", "TRUE", "True", " true ", "\ttrue\n", "1", " 1 "}) {
            FlagResolution r = FlagResolution.of(false, false, raw, null);
            assertTrue(r.value(), () -> "property " + quote(raw) + " must resolve ON");
            assertEquals(Source.PROPERTY, r.source(), () -> "source for " + quote(raw));
        }
        for (String raw : new String[] {"true", " TRUE ", "1"}) {
            FlagResolution r = FlagResolution.of(false, false, null, raw);
            assertTrue(r.value(), () -> "env " + quote(raw) + " must resolve ON");
            assertEquals(Source.ENV, r.source(), () -> "source for env " + quote(raw));
        }
    }

    /** OFF side of requirement 1 — the pressed kill switch. */
    @Test
    void recognisedFalsyValuesAreOff_trimmedAndCaseInsensitive() {
        for (String raw : new String[] {"false", "FALSE", " False ", "0", " 0\n"}) {
            FlagResolution r = FlagResolution.of(true, true, raw, null);
            assertFalse(r.value(), () -> "property " + quote(raw) + " must resolve OFF");
            assertEquals(Source.PROPERTY, r.source(), () -> "source for " + quote(raw));
        }
        FlagResolution env = FlagResolution.of(true, true, null, " false ");
        assertFalse(env.value(), "env \" false \" must resolve OFF");
        assertEquals(Source.ENV, env.source());
    }

    /** Requirement 1: blank or absent is NOT configuration, so the flag's default stands. */
    @Test
    void blankOrAbsentKeepsTheDefault_notTheSafeSide() {
        for (String raw : new String[] {null, "", "   ", "\t\n"}) {
            FlagResolution on = FlagResolution.of(true, false, raw, raw);
            assertTrue(on.value(), () -> "unconfigured (" + quote(raw) + ") must keep default ON");
            assertEquals(Source.DEFAULT, on.source(), () -> "source for " + quote(raw));

            FlagResolution off = FlagResolution.of(false, true, raw, raw);
            assertFalse(off.value(), () -> "unconfigured (" + quote(raw) + ") must keep default OFF");
            assertEquals(Source.DEFAULT, off.source());
        }
    }

    /** Requirement 2: an unrecognised value is never guessed at — it goes to the safe side. */
    @Test
    void unrecognisedValueResolvesToTheSafeSide_andEchoesWhatItSaw() {
        for (String raw : new String[] {"yes", "fasle", "no", "on", "off", "TRUE!", "2", "-1"}) {
            FlagResolution killSwitch = FlagResolution.of(true, false, raw, null);
            assertFalse(killSwitch.value(),
                    () -> quote(raw) + " must resolve to the safe side (OFF) for a default-ON flag");
            assertEquals(Source.UNRECOGNIZED, killSwitch.source(), () -> "source for " + quote(raw));
            assertEquals(raw.trim(), killSwitch.detail(),
                    () -> "the logged detail must echo the offending value: " + quote(raw));
            assertEquals("unrecognized(" + raw.trim() + ")", killSwitch.describe());

            // The safe side is the parameter, not a constant: a default-OFF flag whose safe side is
            // ON would resolve the same garbage the other way.
            assertTrue(FlagResolution.of(false, true, raw, null).value(),
                    () -> quote(raw) + " must resolve to whatever safeValue says");
        }
    }

    /** Requirement 1: property wins — but a BLANK property is not configuration and must not shadow. */
    @Test
    void propertyWinsOverEnv_butBlankPropertyDoesNotBlockAConfiguredEnv() {
        assertFalse(FlagResolution.of(true, false, "false", "true").value(), "property false wins");
        assertEquals(Source.PROPERTY, FlagResolution.of(true, false, "false", "true").source());
        assertTrue(FlagResolution.of(false, false, "true", "false").value(), "property true wins");

        FlagResolution blankProperty = FlagResolution.of(true, false, "   ", "false");
        assertFalse(blankProperty.value(), "a blank property must not shadow a configured env var");
        assertEquals(Source.ENV, blankProperty.source(), "the env var is what was configured");

        // Unrecognised in the property still wins: it is configuration, just bad configuration.
        FlagResolution garbageProperty = FlagResolution.of(true, false, "yes", "true");
        assertFalse(garbageProperty.value(),
                "a garbled property must not silently fall through to the env var");
        assertEquals(Source.UNRECOGNIZED, garbageProperty.source());
    }

    /** Requirement 2: a failed lookup resolves to the safe side and names the failure. */
    @Test
    void readFailureResolvesToTheSafeSide_andNamesTheException() {
        FlagResolution killSwitch =
                FlagResolution.readFailure(false, new SecurityException("denied"));
        assertFalse(killSwitch.value(), "a read failure must leave a class-A kill switch pressable");
        assertEquals(Source.READ_FAILURE, killSwitch.source());
        assertEquals("read-failure(SecurityException)", killSwitch.describe(),
                "an operator must be able to tell a broken lookup from a deliberate OFF");
        assertTrue(FlagResolution.readFailure(true, new IllegalStateException()).value(),
                "the safe side is the parameter here too");
    }

    /** Log hygiene: an unrecognised value is unvalidated input heading into a log line. */
    @Test
    void unrecognisedDetailIsSanitisedAndBounded() {
        FlagResolution injected = FlagResolution.of(true, false, "tr\nue INFO fake log line", null);
        assertEquals(Source.UNRECOGNIZED, injected.source());
        assertFalse(injected.detail().contains("\n"), "no newline may reach the log line");
        assertTrue(injected.detail().startsWith("tr?ue"), "control chars are replaced, not dropped");

        FlagResolution bounded = FlagResolution.of(true, false, "x".repeat(200), null);
        assertEquals("x".repeat(32) + "...", bounded.detail(), "the echoed value is bounded");
    }

    /**
     * Review R1 finding 4: this type is public, so its canonical constructor — not only the factories
     * — is a way in, and it must enforce the same invariants {@link FlagSwitch} logs against.
     */
    @Test
    void theCanonicalConstructorEnforcesTheInvariants() {
        assertThrows(NullPointerException.class,
                () -> new FlagResolution(true, null, "x"),
                "a resolution that cannot name its source cannot satisfy requirement 5; rejecting it"
                + " at construction beats failing later inside the logging step");

        FlagResolution injected =
                new FlagResolution(false, Source.UNRECOGNIZED, "x\r\n\u001b[31mFAKE INFO line");
        assertEquals("x??\u001b[31mFAKE INFO line".replace("\u001b", "?"), injected.detail(),
                "control characters must be neutralised on every construction path, not just parse()");
        assertFalse(injected.describe().contains("\r"), "no CR may reach the log line");
        assertFalse(injected.describe().contains("\n"), "no LF may reach the log line");
        assertEquals("x".repeat(32) + "...",
                new FlagResolution(false, Source.UNRECOGNIZED, "x".repeat(64)).detail(),
                "the length cap applies to the constructor too");

        FlagResolution clean = new FlagResolution(true, Source.ENV, null);
        assertNull(clean.detail(), "a null detail stays null (sources that need no detail)");
    }

    /** The {@code source=} half of the requirement-5 log line. */
    @Test
    void describeNamesEverySource() {
        assertEquals("property", FlagResolution.of(true, false, "true", null).describe());
        assertEquals("env", FlagResolution.of(true, false, null, "true").describe());
        assertEquals("default", FlagResolution.of(true, false, null, null).describe());
        assertEquals("unrecognized(yes)", FlagResolution.of(true, false, "yes", null).describe());
        assertEquals("read-failure(RuntimeException)",
                FlagResolution.readFailure(false, new RuntimeException()).describe());
    }

    private static String quote(String raw) {
        return raw == null ? "null" : "\"" + raw.replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
}
