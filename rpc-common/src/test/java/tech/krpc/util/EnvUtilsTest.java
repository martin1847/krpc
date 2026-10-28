package tech.krpc.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import tech.krpc.util.EnvUtils.AppEnv;

/**
 * Contract for {@link EnvUtils#normalize(String)} — the APP_ENV deployment-label parser.
 *
 * <p>Tests the pure function directly (package-private, hence this package) rather than
 * {@link EnvUtils#current()}, so nothing here depends on the process environment.
 *
 * <p>The parser's contract: case-insensitive, whitespace-trimming, alias-normalising, and it
 * NEVER throws. The no-throw invariant is the regression this whole change exists for — the old
 * code did {@code AppEnv.valueOf(raw.toUpperCase())}, which threw {@code IllegalArgumentException}
 * on any alias/unknown value (e.g. {@code stage}) and crashed startup on the banner-display path.
 */
class EnvUtilsTest {

    /**
     * The full recognised-label matrix: every canonical name, every alias, and mixed-case
     * spellings. Covers each non-default branch of the {@code switch} at least once, and pins
     * case-insensitivity ({@code STAGE}, {@code Staging}, {@code DeV}). JUnit converts the second
     * column to the {@link AppEnv} constant, so the assertion is against the enum, not a string.
     */
    @ParameterizedTest(name = "normalize(\"{0}\") -> {1}")
    @CsvSource({
            // canonical
            "dev,         DEV",
            "test,        TEST",
            "staging,     STAGING",
            "prod,        PROD",
            // aliases
            "stage,       STAGING",
            "pre,         STAGING",
            "production,  PROD",
            "develop,     DEV",
            "development, DEV",
            // case-insensitivity (upper / title / mixed)
            "STAGE,       STAGING",
            "Staging,     STAGING",
            "PROD,        PROD",
            "DeV,         DEV",
    })
    void recognisedLabels_mapToTheirCanonicalEnv(String raw, AppEnv expected) {
        assertEquals(expected, EnvUtils.normalize(raw));
    }

    /** The method trims surrounding whitespace before matching, so a padded alias still resolves. */
    @Test
    void surroundingWhitespace_isTrimmedBeforeMatching() {
        assertEquals(AppEnv.STAGING, EnvUtils.normalize(" stage "));
    }

    /** APP_ENV unset (null) or blank is the smoothest-for-local-dev default: DEV. */
    @ParameterizedTest(name = "normalize(''{0}'') -> DEV")
    @NullSource
    @EmptySource
    @ValueSource(strings = {"   ", "\t"})
    void unsetOrBlank_defaultsToDev(String raw) {
        assertEquals(AppEnv.DEV, EnvUtils.normalize(raw));
    }

    /**
     * The regression guard: an unrecognised APP_ENV must NOT throw and must fail closed to PROD
     * (the safe side). The old {@code AppEnv.valueOf(raw.toUpperCase())} threw
     * {@code IllegalArgumentException} here, crashing startup — this is exactly the crash the
     * normalize() rewrite fixed.
     */
    @Test
    void unknownValue_doesNotThrow_andFallsBackToProd() {
        assertDoesNotThrow(() -> EnvUtils.normalize("qa"));
        assertEquals(AppEnv.PROD, EnvUtils.normalize("foobar"));
    }
}
