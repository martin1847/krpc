package tech.krpc.arch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * ARCH-002 — reads Gradle's AUTHORITATIVE evaluated project model, emitted by the
 * {@code writeArchInventory} task (see arch-test/build.gradle) into
 * {@code build/arch-inventory/}. Using Gradle's own model (not a hand-rolled
 * {@code settings.gradle} text parser) means the scan-face and NS-1 gates observe every
 * included project regardless of {@code include} syntax (single/double quote,
 * parenthesized, multiline), including intermediate projects and custom {@code projectDir}.
 */
final class BuildInventory {

    private BuildInventory() {}

    /** One included subproject: Gradle path, real dir, and main (production) source dirs. */
    static final class Module {
        /** Gradle path with the leading colon stripped, e.g. {@code rpc-api}, {@code examples:quickstart}. */
        final String name;
        final Path projectDir;
        final List<Path> mainSrcDirs;

        Module(String name, Path projectDir, List<Path> mainSrcDirs) {
            this.name = name;
            this.projectDir = projectDir;
            this.mainSrcDirs = mainSrcDirs;
        }
    }

    private static Path inventoryDir() {
        String dir = System.getProperty("arch.inventory.dir");
        assertTrue(dir != null && !dir.isBlank(),
            "arch.inventory.dir system property is unset — the writeArchInventory task must run"
            + " before this test (see arch-test/build.gradle test { dependsOn writeArchInventory }).");
        Path p = Paths.get(dir);
        assertTrue(Files.isDirectory(p), "arch inventory dir does not exist: " + p);
        return p;
    }

    /** Name (colon-stripped Gradle path) -> module, in build order. */
    static Map<String, Module> modules() {
        Path file = inventoryDir().resolve("projects.tsv");
        assertTrue(Files.isRegularFile(file), "inventory projects.tsv missing: " + file);
        Map<String, Module> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split("\t", -1);
                assertTrue(cols.length >= 2, "malformed inventory row: " + line);
                String name = stripLeadingColon(cols[0]);
                Path projectDir = Paths.get(cols[1]);
                List<Path> srcDirs = new ArrayList<>();
                if (cols.length >= 3 && !cols[2].isBlank()) {
                    for (String s : cols[2].split(java.io.File.pathSeparator)) {
                        if (!s.isBlank()) {
                            srcDirs.add(Paths.get(s));
                        }
                    }
                }
                out.put(name, new Module(name, projectDir, srcDirs));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(out.containsKey("rpc-api"),
            "sanity: Gradle project inventory must contain the core modules: " + out.keySet());
        return out;
    }

    /** arch-test's testImplementation project(...) paths (the scan face), colon-stripped. */
    static java.util.Set<String> scannedDeps() {
        Path file = inventoryDir().resolve("scanned-deps.txt");
        assertTrue(Files.isRegularFile(file), "inventory scanned-deps.txt missing: " + file);
        java.util.Set<String> out = new TreeSet<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(stripLeadingColon(line.trim()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static String stripLeadingColon(String path) {
        return path.startsWith(":") ? path.substring(1) : path;
    }
}
