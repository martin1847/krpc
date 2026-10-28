package tech.krpc.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ARCH-002 — scan-face completeness guard (anti "hardcoded scan-face silent miss").
 *
 * <p>{@link ArchitectureTest.OnlyCoreModules#MODULES} and arch-test's
 * {@code testImplementation project(...)} list are a HARDCODED scan face: a new core
 * module added to the build but not to arch-test would be silently UNSCANNED — its classes
 * never enter the ArchUnit analysis, so R1..R4 pass vacuously for it (a false green). This
 * is the "hardcoded scan-face silent miss" class the NORTH_STAR doctrine forbids.
 *
 * <p>The authoritative module set comes from Gradle's EVALUATED PROJECT MODEL (emitted by
 * the {@code writeArchInventory} task; see {@link BuildInventory}), NOT a hand-rolled
 * {@code settings.gradle} text parser. A text parser silently ignores unsupported syntax
 * ({@code include "x"}, {@code include('x')}, multiline) and would let a module escape;
 * reading Gradle's own model means every included project is seen regardless of include
 * form, including intermediate projects (e.g. {@code :examples}) and custom {@code projectDir}.
 * This test then forces EVERY included module to be consciously classified as SCANNED or
 * EXCLUDED; a new one that is neither turns RED naming it (mirrors R2's completeness guard).
 *
 * <p>Plain JUnit test, not an {@link com.tngtech.archunit.lang.ArchRule}: the fact under
 * test is the build's project graph, not bytecode. No frozen store entry; ratchet untouched.
 */
class ScanFaceCompletenessTest {

    private final Path repoRoot = ArchitectureTest.OnlyCoreModules.repoRoot();

    // The scan face: the modules ArchUnit actually analyzes (ADR-0005 "six core modules").
    private static final Set<String> SCANNED =
        new TreeSet<>(Arrays.asList(ArchitectureTest.OnlyCoreModules.MODULES));

    /**
     * Modules deliberately OUTSIDE the ArchUnit core scan, each with a reviewed reason.
     * ADDING to this set is the conscious escape hatch (mirrors R2's layer assignment):
     * a new module must be classified here OR added to the scan face, never left dangling.
     *
     * <ul>
     *   <li>{@code test-api}, {@code test-jwks}, {@code test-server-spring},
     *       {@code test-server} — test-only fixture / demo modules ({@code test-*}); their
     *       {@code src/test} are {@code main()}-based demos, not the production contract graph.</li>
     *   <li>{@code rpc-client-spring}, {@code rpc-server-spring} — Spring Boot autoconfig
     *       ADAPTER modules ({@code tech.krpc.{client,server}.spring}). ADR-0005 scopes the
     *       gate to the six transport/runtime core modules; DI-framework integration glue is
     *       consumer-facing surface, not the core contract graph. (Policy-review trigger: if a
     *       Spring adapter grows runtime behavior, revisit adapter ownership — see ADR-0005
     *       ARCH-002 addendum. This guard forces that decision rather than silent drift.)</li>
     *   <li>{@code ext-rpc-gen} — build-time codegen tool ({@code tech.krpc.ext.gen}); it emits
     *       client stubs, it is not part of the runtime contract graph (ADR-0005 exclusion).</li>
     *   <li>{@code arch-test} — the gate module itself; it owns no production classes.</li>
     *   <li>{@code examples} — the examples aggregator project (parent of examples:quickstart;
     *       Gradle materializes it as an intermediate project); unpublished, no production classes.</li>
     *   <li>{@code examples:quickstart} — unpublished example, not production.</li>
     * </ul>
     */
    private static final Set<String> EXCLUDED = new TreeSet<>(Arrays.asList(
        "test-api", "test-jwks", "test-server-spring", "test-server",
        "rpc-client-spring", "rpc-server-spring",
        "ext-rpc-gen",
        "arch-test",
        "examples", "examples:quickstart"));

    @Test
    void everyIncludedModuleIsConsciouslyClassified() {
        Map<String, BuildInventory.Module> inventory = BuildInventory.modules();
        Set<String> includes = new TreeSet<>(inventory.keySet());

        // Every included project dir Gradle reported must actually exist on disk (honesty:
        // the inventory names real projects, never a phantom the walk would treat as empty).
        for (BuildInventory.Module m : inventory.values()) {
            assertTrue(Files.isDirectory(m.projectDir),
                "included project " + m.name + " has a non-existent projectDir: " + m.projectDir);
        }

        // 1) THE ARCH-002 CATCH: a project Gradle includes that is neither scanned nor
        //    explicitly excluded is an unclassified silent miss -> RED, naming it.
        Set<String> unclassified = new TreeSet<>(includes);
        unclassified.removeAll(SCANNED);
        unclassified.removeAll(EXCLUDED);
        assertTrue(unclassified.isEmpty(),
            "Gradle includes project(s) not classified by arch-test: " + unclassified
            + " — a new core module must be ADDED to ArchitectureTest.OnlyCoreModules.MODULES"
            + " (and arch-test/build.gradle testImplementation) so ArchUnit scans it,"
            + " OR added to ScanFaceCompletenessTest.EXCLUDED with a documented reason."
            + " Leaving it unclassified would let R1..R4 pass vacuously for it (false green).");

        // 2) The scan face may not name a module the build does not include (no phantom).
        Set<String> scannedNotIncluded = new TreeSet<>(SCANNED);
        scannedNotIncluded.removeAll(includes);
        assertTrue(scannedNotIncluded.isEmpty(),
            "OnlyCoreModules.MODULES names module(s) absent from the build: " + scannedNotIncluded);

        // 3) The exclusion list may not carry a stale entry (keeps the allowlist honest).
        Set<String> excludedNotIncluded = new TreeSet<>(EXCLUDED);
        excludedNotIncluded.removeAll(includes);
        assertTrue(excludedNotIncluded.isEmpty(),
            "ScanFaceCompletenessTest.EXCLUDED has stale entries absent from the build: "
            + excludedNotIncluded);

        // 4) A module cannot be both scanned and excluded.
        Set<String> both = new TreeSet<>(SCANNED);
        both.retainAll(EXCLUDED);
        assertTrue(both.isEmpty(), "module(s) both scanned and excluded: " + both);
    }

    @Test
    void scanFaceModuleListMatchesClasspathDeps() {
        // Internal consistency of the scan face: OnlyCoreModules.MODULES must equal arch-test's
        // testImplementation project(...) set (from Gradle's own dependency model). A scanned
        // module missing from the classpath imports 0 classes (false green); a classpath module
        // missing from MODULES is scanned but unpinned.
        Set<String> deps = BuildInventory.scannedDeps();
        assertEquals(new LinkedHashSet<>(SCANNED), new LinkedHashSet<>(deps),
            "OnlyCoreModules.MODULES and arch-test/build.gradle testImplementation project(...) "
            + "must be identical. MODULES=" + SCANNED + " testImplementation=" + deps);
    }

    @Test
    void everyOnDiskModuleDirectoryIsClassified() throws IOException {
        // Independent filesystem corroboration (does NOT read the Gradle inventory): every
        // first-level directory that carries a build.gradle is a real module on disk and MUST
        // be classified. Catches a module dir added on disk (a would-be include) before it can
        // slip in unscanned. Nested modules (e.g. examples/quickstart) are covered via their
        // Gradle path in everyIncludedModuleIsConsciouslyClassified. A dir that carries its OWN
        // settings.gradle is a nested STANDALONE build (e.g. benchmark — "a separate build"),
        // not a subproject of this build, so it is skipped.
        Set<String> classified = new TreeSet<>(SCANNED);
        classified.addAll(EXCLUDED);
        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> top = Files.list(repoRoot)) {
            top.filter(Files::isDirectory)
                .filter(d -> Files.isRegularFile(d.resolve("build.gradle")))
                .filter(d -> !Files.isRegularFile(d.resolve("settings.gradle")))
                .forEach(d -> {
                    String name = d.getFileName().toString();
                    if (!classified.contains(name)) {
                        offenders.add(name);
                    }
                });
        }
        assertTrue(offenders.isEmpty(),
            "on-disk module directory(ies) with a build.gradle not classified by arch-test: "
            + offenders + " — add to OnlyCoreModules.MODULES (scan) or EXCLUDED (with a reason).");
    }
}
