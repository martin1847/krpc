package tech.krpc.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * ARCH-001 — build-failing architecture gate for krpc core.
 *
 * <p>Makes the umbrella NORTH_STAR principles executable (see docs/decisions/
 * ADR-0005-architecture-gates.md). Every rule bites a whole violation <em>class</em>
 * (not one planted instance) and is wrapped in {@link FreezingArchRule}: the committed
 * text stores under {@code arch-test/archunit_store/} are a one-way ratchet — existing
 * violations are grandfathered, any NEW violation fails the build, and the baseline only
 * shrinks (see archunit.properties).
 * <p>Analysis subject = ONLY the classes we own — the six core modules (rpc-api,
 * rpc-common, rpc-client, rpc-server, rpc-server-quarkus, http-server), pinned by the
 * {@link OnlyCoreModules} location filter so transitive {@code tech.krpc.*} jars (e.g.
 * the published {@code tech.krpc.ext:ext-rpc} pulled in by rpc-server-quarkus) are NEVER
 * analyzed. Test classes are excluded (production classes only). R3/R4 constrain ALL
 * production classes under {@code tech.krpc..} (no allowlist), and a completeness guard
 * fails the build if any owned production class falls outside the classified layer
 * package roots — so a new unclassified package can never silently escape the rules.
 */
@AnalyzeClasses(
    packages = "tech.krpc",
    importOptions = {ImportOption.DoNotIncludeTests.class, ArchitectureTest.OnlyCoreModules.class})
public final class ArchitectureTest {

    // Pins analysis to THIS repo's six core modules by ABSOLUTE-PATH OWNERSHIP, computed
    // entirely in java.nio.Path — never string/URI prefix matching, which breaks on
    // symlinked project dirs (logical vs physical) and on percent-encoded spaces (%20 vs
    // " "). Repo root = parent of the arch-test project dir (Gradle sets the test JVM
    // working dir to that project dir; verified), canonicalized. A class location is owned
    // iff its on-disk file (the jar for jar: URIs, the class file for file: URIs),
    // canonicalized, is Path.startsWith one of "<repoRoot>/<module>/build". This kills the
    // whole scope-substitution class: a Gradle-cache jar, an .m2 jar, ANOTHER checkout
    // (/opt/vendor/krpc/rpc-client/build/...), or a relocated buildDir all fall outside the
    // owned build dirs and are rejected (RED, never silently green). Non-file/jar or
    // unparseable URIs are rejected, not crashed. Any residual path-representation exotica
    // can only cause a false RED (gate fails loudly), never a false green — see ADR-0005
    // limitations. Adding a core module is a conscious edit to MODULES here.
    public static final class OnlyCoreModules implements ImportOption {
        static final String[] MODULES = {
            "rpc-api", "rpc-common", "rpc-client",
            "rpc-server", "rpc-server-quarkus", "http-server"
        };
        private static final List<Path> OWNED_BUILD_DIRS = ownedBuildDirs();

        /** Canonical absolute path of this repo checkout's root. */
        static Path repoRoot() {
            return canonical(Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().getParent());
        }

        private static List<Path> ownedBuildDirs() {
            Path root = repoRoot();
            List<Path> dirs = new ArrayList<>(MODULES.length);
            for (String module : MODULES) {
                // NB5 guard: the module dir and its build/ MUST be plain directories, never
                // symlinks. An intra-repo symlink (e.g. rpc-api/build -> ../rpc-common/build,
                // or -> build-old) would canonicalize to a path still under repoRoot and thus
                // slip past requireInsideRoot, silently substituting/duplicating scope.
                requireNotSymlinked(root, module);
                Path anchor = canonical(root.resolve(module).resolve("build"));
                // NB4 guard (defense in depth): the canonicalized anchor must stay under the
                // canonical repo root — a build dir symlinked OUTSIDE would otherwise be owned.
                requireInsideRoot(root, anchor);
                dirs.add(anchor);
            }
            return dirs;
        }

        /** Fail loudly if a module's dir or its build/ is a symlink (anchors must be plain
         *  directories). Checks the lexical paths under the canonical root, so symlinked
         *  ancestors ABOVE the root (e.g. macOS /tmp -> /private/tmp) are tolerated. */
        static void requireNotSymlinked(Path repoRoot, String module) {
            Path moduleDir = repoRoot.resolve(module);
            Path buildDir = moduleDir.resolve("build");
            for (Path anchor : new Path[] {moduleDir, buildDir}) {
                if (Files.isSymbolicLink(anchor)) {
                    throw new IllegalStateException(
                        "arch-test scope anchor is a symlink: " + anchor
                        + " — module dirs and their build/ must be plain directories;"
                        + " refusing to run (would substitute/duplicate scope).");
                }
            }
        }

        /** Fail loudly if a (canonicalized) owned anchor escapes the (canonical) repo root. */
        static void requireInsideRoot(Path repoRoot, Path anchor) {
            if (!anchor.startsWith(repoRoot)) {
                throw new IllegalStateException(
                    "arch-test scope anchor escapes the repo root: " + anchor
                    + " is not under " + repoRoot
                    + " — a module build dir is symlinked outside the checkout; refusing to run"
                    + " (would admit external classes as owned).");
            }
        }

        /** Real (symlink-resolved) path if it exists, else lexical absolute-normalized. */
        private static Path canonical(Path p) {
            try {
                return p.toRealPath();
            } catch (IOException e) {
                return p.toAbsolutePath().normalize();
            }
        }

        /** The on-disk file a class location lives in: the jar for jar: URIs, the class
         *  file for file: URIs. Null for any other/unparseable URI (=> not owned). */
        static Path fileOf(URI uri) {
            try {
                String scheme = uri.getScheme();
                if ("file".equals(scheme)) {
                    return Paths.get(uri);
                }
                if ("jar".equals(scheme)) {
                    String ssp = uri.getRawSchemeSpecificPart(); // e.g. file:/…/x.jar!/entry
                    int bang = ssp.indexOf("!/");
                    String filePart = bang >= 0 ? ssp.substring(0, bang) : ssp;
                    return Paths.get(URI.create(filePart));
                }
                return null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** True iff the URI's file is inside one of this repo's six modules' build dirs. */
        static boolean isOwned(URI uri) {
            Path file = fileOf(uri);
            if (file == null) {
                return false;
            }
            Path canonicalFile = canonical(file);
            for (Path buildDir : OWNED_BUILD_DIRS) {
                if (canonicalFile.startsWith(buildDir)) {
                    return true;
                }
            }
            return false;
        }

        /** True iff the URI's file is inside the given module's build dir. */
        static boolean ownedBy(URI uri, String module) {
            Path file = fileOf(uri);
            if (file == null) {
                return false;
            }
            return canonical(file)
                .startsWith(canonical(repoRoot().resolve(module).resolve("build")));
        }

        @Override
        public boolean includes(Location location) {
            return isOwned(location.asURI());
        }
    }

    // Package roots derived from the actual code (not guessed):
    //   api    = tech.krpc.annotation, tech.krpc.model            (rpc-api)
    //   common = tech.krpc.{common,context,filter,internal,serial,util} (rpc-common)
    //   client = tech.krpc.client                                 (rpc-client)
    //   server = tech.krpc.server                                 (rpc-server + rpc-server-quarkus)
    //   http   = tech.krpc.http                                   (http-server)
    private static final String[] API_PKGS = {
        "tech.krpc.annotation..", "tech.krpc.model.."
    };
    private static final String[] COMMON_PKGS = {
        "tech.krpc.common..", "tech.krpc.context..", "tech.krpc.filter..",
        "tech.krpc.internal..", "tech.krpc.serial..", "tech.krpc.util.."
    };
    private static final String CLIENT_PKG = "tech.krpc.client..";
    private static final String SERVER_PKG = "tech.krpc.server..";
    private static final String HTTP_PKG = "tech.krpc.http..";

    // ---- R1 (structure) — NS: "anti-rot gates fail builds"; keeps slices acyclic ----
    // Global slicing on the merged classpath: cycles are detected across module
    // boundaries too, which is strictly stronger than per-module slicing.
    @ArchTest
    static final ArchRule r1_no_package_cycles = FreezingArchRule.freeze(
        slices().matching("tech.krpc.(*)..")
            .should().beFreeOfCycles()
            .as("R1: tech.krpc slices are free of package cycles"));

    // ---- R2 (layering) — NS-1/NS-2: interface/api is the contract; dependency direction ----
    // rpc-api must not depend on common/client/server/http internals.
    @ArchTest
    static final ArchRule r2_api_independent = FreezingArchRule.freeze(
        noClasses().that().resideInAnyPackage(API_PKGS)
            .should().dependOnClassesThat().resideInAnyPackage(concat(
                COMMON_PKGS, CLIENT_PKG, SERVER_PKG, HTTP_PKG))
            .as("R2: rpc-api must not depend on common/client/server/http"));

    // rpc-common must not depend on client/server/http (it is below them).
    @ArchTest
    static final ArchRule r2_common_below_runtime = FreezingArchRule.freeze(
        noClasses().that().resideInAnyPackage(COMMON_PKGS)
            .should().dependOnClassesThat().resideInAnyPackage(CLIENT_PKG, SERVER_PKG, HTTP_PKG)
            .as("R2: rpc-common must not depend on client/server/http"));

    // rpc-client and rpc-server must not depend on each other.
    @ArchTest
    static final ArchRule r2_client_not_server = FreezingArchRule.freeze(
        noClasses().that().resideInAPackage(CLIENT_PKG)
            .should().dependOnClassesThat().resideInAPackage(SERVER_PKG)
            .as("R2: rpc-client must not depend on rpc-server"));

    @ArchTest
    static final ArchRule r2_server_not_client = FreezingArchRule.freeze(
        noClasses().that().resideInAPackage(SERVER_PKG)
            .should().dependOnClassesThat().resideInAPackage(CLIENT_PKG)
            .as("R2: rpc-server must not depend on rpc-client"));

    // Completeness guard (NS anti-vacuous-green): every production class under tech.krpc..
    // must reside in one of the classified layer roots above. A new, unclassified package
    // (e.g. tech.krpc.newpkg) turns the build RED and forces a conscious layer assignment,
    // so R2's direction rules can never be silently bypassed by an unclassified package.
    @ArchTest
    static final ArchRule r2_all_classes_classified = FreezingArchRule.freeze(
        classes().that().resideInAPackage("tech.krpc..")
            .should().resideInAnyPackage(allCorePkgs())
            .as("R2: every tech.krpc production class resides in a classified layer package (completeness guard)"));

    // ---- R3 — NS-3: infra belongs to the platform. Core must not grow discovery /
    //      registry / load-balancing / telemetry-backend dependencies. ----
    private static final String[] INFRA_DENYLIST = {
        "io.kubernetes..",            // k8s java client
        "io.fabric8..",               // fabric8 k8s client
        "com.ecwid.consul..",         // consul
        "com.orbitz.consul..",        // consul (alt client)
        "com.netflix..",              // eureka / ribbon / archaius
        "org.apache.zookeeper..",     // zookeeper
        "org.apache.curator..",       // zookeeper recipes
        "io.etcd..",                  // etcd (jetcd)
        "com.alibaba.nacos..",        // nacos discovery/config
        "org.springframework.cloud..",// spring cloud discovery/lb
        "io.micrometer..",            // telemetry backend registry
        "io.opentelemetry.sdk.."      // OTel SDK export backend (API-only is fine)
    };

    @ArchTest
    static final ArchRule r3_no_infra_backends = FreezingArchRule.freeze(
        noClasses().that().resideInAPackage("tech.krpc..")
            .should().dependOnClassesThat().resideInAnyPackage(INFRA_DENYLIST)
            .as("R3: core must not depend on discovery/registry/LB/telemetry-backend packages (NS-3)"));

    // ---- R4 — NS-7: native-image first-class. No JDK-internal / open-world APIs. ----
    @ArchTest
    static final ArchRule r4_no_jdk_internal = FreezingArchRule.freeze(
        noClasses().that().resideInAPackage("tech.krpc..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "sun..", "com.sun..", "jdk.internal..")
            .as("R4: core must not depend on sun../com.sun../jdk.internal.. (NS-7)"));

    private static String[] allCorePkgs() {
        return concatAll(API_PKGS, COMMON_PKGS, CLIENT_PKG, SERVER_PKG, HTTP_PKG);
    }

    private static String[] concat(String[] base, String... extra) {
        String[] out = new String[base.length + extra.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }

    private static String[] concatAll(String[] a, String[] b, String... extra) {
        return concat(concat(a, b), extra);
    }
}
