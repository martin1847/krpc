package tech.krpc.arch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * NS-1 gate — the interface IS the contract; service authors write NO {@code .proto} files.
 *
 * <p>The umbrella NORTH_STAR NS-1 says KRPC is interface-first: a service is a Java
 * interface + DTOs, and "service authors write no proto files" (SPEC §"Interface-first").
 * This gate makes that structural: NO {@code *.proto} may live under any PRODUCTION
 * module's source. A hand-authored proto there turns the build RED.
 *
 * <p>Module dirs come from Gradle's evaluated project model (see {@link BuildInventory}), not
 * a {@code settings.gradle} text parser or a {@code repoRoot/<name>/src} convention guess —
 * so a module declared with any include form or a custom {@code projectDir} is still scanned
 * at its real location. For each production module the scan covers the UNION of (a) the whole
 * {@code <projectDir>/src} tree — which catches a hand-authored {@code src/main/proto/x.proto}
 * even though no protobuf plugin registers {@code proto/} as a source dir — and (b) every
 * registered main source-set directory reported by Gradle (which catches a source root placed
 * OUTSIDE {@code src}). Production modules = every included project except non-production ones
 * ({@code test-*}, {@code examples*}, and the {@code arch-test} gate).
 *
 * <p>Real state (verified): the sole wire-envelope proto ({@code InputProto}/
 * {@code OutputProto}/{@code SerialEnum}) lives at repo-top-level {@code proto/internal.proto},
 * NOT under any module's source; the generated Java is checked in under {@code rpc-common}. So
 * the production scan is empty and {@link #ALLOWLIST} is empty. Should a wire-envelope proto
 * ever be moved INTO a module source tree, add its exact repo-relative path to the allowlist
 * with a comment.
 *
 * <p>Plain JUnit test, not an {@link com.tngtech.archunit.lang.ArchRule}: the fact under
 * test is source files on disk, which a bytecode analyzer cannot see. No frozen store entry.
 */
class NoProtoInProductionSourceTest {

    private final Path repoRoot = ArchitectureTest.OnlyCoreModules.repoRoot();

    /**
     * Repo-relative paths of {@code .proto} files explicitly permitted under a production
     * module source (e.g. a framework wire-envelope proto), each justified by a comment.
     * EMPTY today: the only envelope proto lives at top-level {@code proto/}, outside src.
     */
    private static final Set<String> ALLOWLIST = new TreeSet<>(List.of());

    /** A production (runtime / runtime-adjacent) module: not a test fixture, example, or the gate. */
    private static boolean isProduction(String name) {
        return !name.startsWith("test-") && !name.startsWith("examples") && !name.equals("arch-test");
    }

    @Test
    void noProtoFilesUnderAnyProductionModuleSource() throws IOException {
        Map<String, BuildInventory.Module> inventory = BuildInventory.modules();
        Set<String> offenders = new TreeSet<>();
        int scannedModules = 0;
        for (BuildInventory.Module m : inventory.values()) {
            if (!isProduction(m.name)) {
                continue;
            }
            scannedModules++;
            assertTrue(Files.isDirectory(m.projectDir),
                "production module " + m.name + " has a non-existent projectDir (Gradle project"
                + " model incomplete?): " + m.projectDir);

            // Union of the whole src tree and each registered main source-set root. offenders is
            // a Set, so overlapping roots never double-count.
            Set<Path> roots = new LinkedHashSet<>();
            roots.add(m.projectDir.resolve("src"));
            roots.addAll(m.mainSrcDirs);
            for (Path root : roots) {
                if (!Files.isDirectory(root)) {
                    continue; // module with no such source root
                }
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".proto"))
                        .forEach(p -> {
                            String rel = repoRoot.relativize(p).toString().replace('\\', '/');
                            if (!ALLOWLIST.contains(rel)) {
                                offenders.add(rel);
                            }
                        });
                }
            }
        }
        assertTrue(scannedModules >= 6,
            "sanity: expected to scan at least the six core production modules, saw " + scannedModules);
        assertTrue(offenders.isEmpty(),
            "NS-1 violated: .proto file(s) authored under production module source — KRPC is"
            + " interface-first (Java interface + DTOs, no proto authoring). Offenders: "
            + offenders + ". If a file is a legitimate framework wire-envelope proto, add its"
            + " repo-relative path to NoProtoInProductionSourceTest.ALLOWLIST with a reason.");
    }
}
