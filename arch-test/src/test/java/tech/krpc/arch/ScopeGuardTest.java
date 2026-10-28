package tech.krpc.arch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ARCH-001 NB2/NB3 — plain (NON-frozen) guard that {@link ArchitectureTest.OnlyCoreModules}
 * anchors ownership to THIS repo's canonical root by ABSOLUTE PATH (java.nio.Path
 * comparison, not string/URI prefixing), closing the whole scope-substitution class.
 * Ownership = the location's on-disk file lives under {@code <repoRoot>/<module>/build};
 * a cache jar, an .m2 jar, another checkout, or a relocated buildDir is rejected and turns
 * this test RED. Path comparison (not raw URI strings) means symlinked project dirs and
 * percent-encoded spaces do not cause false rejections or crashes.
 */
class ScopeGuardTest {

    private final ImportOption filter = new ArchitectureTest.OnlyCoreModules();
    private final Path repoRoot = ArchitectureTest.OnlyCoreModules.repoRoot();

    @Test
    void acceptsOwnedLocalOutputRejectsEverythingElse() {
        // Owned: a local build output of THIS checkout (real root, spaces-safe via toUri()).
        Path ownedJar = repoRoot.resolve("rpc-client").resolve("build")
            .resolve("libs").resolve("rpc-client-1.1.0.jar");
        Location owned = Location.of(URI.create("jar:" + ownedJar.toUri() + "!/tech/krpc/client/Foo.class"));
        // Rejected: Gradle-cache jar of a hypothetical published tech.krpc:rpc-client.
        Location cacheJar = Location.of(URI.create(
            "jar:file:/Users/dev/.gradle/caches/modules-2/files-2.1/tech.krpc/rpc-client/9.9.9/abc/rpc-client-9.9.9.jar!/tech/krpc/client/Foo.class"));
        // Rejected: .m2 jar of the same.
        Location m2Jar = Location.of(URI.create(
            "jar:file:/Users/dev/.m2/repository/tech/krpc/rpc-client/9.9.9/rpc-client-9.9.9.jar!/tech/krpc/client/Foo.class"));
        // Rejected: a DIFFERENT checkout with the same module/build shape.
        Location otherCheckout = Location.of(URI.create(
            "file:/opt/vendor/krpc/rpc-client/build/classes/java/main/tech/krpc/client/Foo.class"));

        assertTrue(filter.includes(owned), "must accept this repo's local build output");
        assertFalse(filter.includes(cacheJar), "must reject external Gradle-cache jar");
        assertFalse(filter.includes(m2Jar), "must reject external .m2 jar");
        assertFalse(filter.includes(otherCheckout), "must reject a different checkout's build output");
    }

    @Test
    void handlesSpacesInPathWithoutCrashing() throws IOException {
        // A REAL owned file under a spaced directory inside a module build dir. Built and
        // matched via java.nio.Path/URI (toUri() -> %20), so no URI.create-on-space crash
        // and the space round-trips correctly to an accept.
        Path spacedOwned = repoRoot.resolve("rpc-api").resolve("build").resolve("tmp")
            .resolve("space test").resolve("Owned.class");
        Files.createDirectories(spacedOwned.getParent());
        Files.write(spacedOwned, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        try {
            assertTrue(filter.includes(Location.of(spacedOwned.toUri())),
                "owned spaced path must be accepted without crashing");
            // A foreign spaced path (percent-encoded) must be rejected, also without crashing.
            Location foreignSpaced = Location.of(URI.create(
                "file:/opt/vendor%20x/krpc/rpc-api/build/x%20y/Foo.class"));
            assertFalse(filter.includes(foreignSpaced),
                "foreign spaced path must be rejected without crashing");
        } finally {
            Files.deleteIfExists(spacedOwned);
            Files.deleteIfExists(spacedOwned.getParent());
        }
    }

    @Test
    void resolvesSymlinkedOwnedPathToOwned() throws IOException {
        // A REAL owned class file under rpc-api/build.
        Path realFile = repoRoot.resolve("rpc-api").resolve("build").resolve("tmp")
            .resolve("sym-owned").resolve("Owned.class");
        Files.createDirectories(realFile.getParent());
        Files.write(realFile, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        // A symlink placed under arch-test/build (NOT an owned module) pointing at rpc-api/build.
        Path link = repoRoot.resolve("arch-test").resolve("build").resolve("tmp")
            .resolve("linked-rpc-api-build");
        Files.createDirectories(link.getParent());
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, repoRoot.resolve("rpc-api").resolve("build"));
        try {
            // Reached VIA the symlink: the literal path is under arch-test/build (unowned),
            // so a raw-string prefix test rejects it (false RED); Path.toRealPath resolves
            // the symlink to rpc-api/build and correctly recognizes ownership.
            Path viaLink = link.resolve("tmp").resolve("sym-owned").resolve("Owned.class");
            assertTrue(filter.includes(Location.of(viaLink.toUri())),
                "a symlinked view of an owned build output must resolve to owned");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(realFile);
            Files.deleteIfExists(realFile.getParent());
        }
    }

    @Test
    void everyCoreModuleContributesOwnedClassesOnly() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(filter)
            .importPackages("tech.krpc");

        for (String module : ArchitectureTest.OnlyCoreModules.MODULES) {
            long n = classes.stream()
                .filter(c -> c.getSource().isPresent()
                    && ArchitectureTest.OnlyCoreModules.ownedBy(c.getSource().get().getUri(), module))
                .count();
            assertTrue(n > 0, "no owned classes analyzed for module " + module
                + " — scope substitution or missing local build output?");
        }

        boolean anyUnowned = classes.stream()
            .anyMatch(c -> c.getSource().isPresent()
                && !ArchitectureTest.OnlyCoreModules.isOwned(c.getSource().get().getUri()));
        assertFalse(anyUnowned, "an analyzed class was not owned by this repo's six modules");
    }

    @Test
    void anchorEscapingRepoRootIsRejected() {
        // NB4: a module build dir symlinked outside the checkout canonicalizes to an
        // external anchor. The guard must refuse to run (loud RED), never admit it.
        Path root = Paths.get("/repo/wt");
        Path inside = Paths.get("/repo/wt/rpc-api/build");
        Path outside = Paths.get("/evil/external/build");
        assertDoesNotThrow(
            () -> ArchitectureTest.OnlyCoreModules.requireInsideRoot(root, inside),
            "an anchor under the repo root must be accepted");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ArchitectureTest.OnlyCoreModules.requireInsideRoot(root, outside),
            "an anchor outside the repo root must be rejected");
        assertTrue(ex.getMessage().contains("escapes the repo root"),
            "rejection message must name the escape");
    }

    @Test
    void symlinkedBuildAnchorIsRejected() throws IOException {
        // NB5: an INTRA-repo symlink (modx/build -> ../mody/build) canonicalizes to a path
        // still under repoRoot, so requireInsideRoot alone would pass it. The anchors must
        // be plain directories; a symlinked module dir OR build dir must be rejected.
        Path root = Files.createTempDirectory("arch-scope");
        try {
            // Plain module with a real build dir -> accepted.
            Files.createDirectories(root.resolve("modok").resolve("build"));
            assertDoesNotThrow(
                () -> ArchitectureTest.OnlyCoreModules.requireNotSymlinked(root, "modok"),
                "a plain module/build must be accepted");

            // modx/build -> ../mody/build (intra-repo redirection) -> rejected.
            Files.createDirectories(root.resolve("mody").resolve("build"));
            Files.createDirectories(root.resolve("modx"));
            Files.createSymbolicLink(
                root.resolve("modx").resolve("build"), root.resolve("mody").resolve("build"));
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArchitectureTest.OnlyCoreModules.requireNotSymlinked(root, "modx"),
                "a symlinked build/ must be rejected");
            assertTrue(ex.getMessage().contains("symlink"), "message must name the symlink");

            // The module dir itself as a symlink -> rejected.
            Files.createDirectories(root.resolve("realmod").resolve("build"));
            Files.createSymbolicLink(root.resolve("modlink"), root.resolve("realmod"));
            assertThrows(IllegalStateException.class,
                () -> ArchitectureTest.OnlyCoreModules.requireNotSymlinked(root, "modlink"),
                "a symlinked module dir must be rejected");
        } finally {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort cleanup of the scratch temp tree
                    }
                });
            }
        }
    }
}
