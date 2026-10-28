package tech.krpc.ext.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import tech.krpc.ext.gen.meta.ApiMetaRoot;
import tech.krpc.ext.gen.meta.Dto;
import tech.krpc.ext.gen.meta.Property;
import tech.krpc.ext.gen.meta.PropertyType;

/**
 * GENDET-002: the single-file TS/Dart/yaml output must emit a referenced DTO BEFORE any DTO
 * that references it (else a consumer building with {@code emitDecoratorMetadata} hits a TDZ
 * forward reference), while staying byte-for-byte deterministic. These tests drive the real
 * {@link Gen#genApiMetaRoot} emit pipeline over hand-built meta models.
 *
 * <p>Superclass edges are deliberately NOT tested: inheritance is flattened upstream
 * ({@code RpcMetaServiceImpl.cls2dto} copies every superclass declared field into
 * {@code Dto.fields}), the meta model carries no superclass reference, and no DTO template
 * emits an {@code extends} clause — so a superclass dependency cannot exist in emitted output.
 * The only representable DTO-to-DTO dependency is via a field type / generic argument, which
 * these tests cover; {@link #noExtendsClauseEmittedForDtos} locks in the no-extends invariant.
 */
class TopoEmitOrderTest {

    // ---- meta-model builders -------------------------------------------------

    /** An emitted DTO node (has ≥1 field so {@code hasChild()} is true), emitted as a TS class. */
    private static Dto dto(String name, Property... fields) {
        Dto d = new Dto();
        d.setName(name);
        d.setInput(true);
        d.setTypeVar(0);
        List<Property> fs = new ArrayList<>();
        for (Property f : fields) {
            fs.add(f);
        }
        d.setFields(fs);
        return d;
    }

    /** A bare type reference by simple name (scalar leaf, or a reference to another node). */
    private static PropertyType type(String name) {
        Dto d = new Dto();
        d.setName(name);
        d.setTypeVar(0);
        return new PropertyType(d);
    }

    /** A generic type reference, e.g. {@code List<Zebra>} or {@code Map<String, List<Zebra>>}. */
    private static PropertyType generic(String rawName, int typeVar, PropertyType... args) {
        Dto d = new Dto();
        d.setName(rawName);
        d.setTypeVar(typeVar);
        return new PropertyType(d, List.of(args));
    }

    private static Property field(String name, PropertyType type) {
        return new Property(name, type, null);
    }

    // ---- emit driver ---------------------------------------------------------

    private static String genTs(Path dir, Dto... dtos) throws IOException {
        List<Dto> list = new ArrayList<>();
        for (Dto d : dtos) {
            list.add(d);
        }
        return genTs(dir, list);
    }

    private static String genTs(Path dir, List<Dto> dtos) throws IOException {
        ApiMetaRoot root = new ApiMetaRoot();
        root.setApp("test");
        root.setApis(new ArrayList<>());
        root.setDtos(new ArrayList<>(dtos));
        Gen.genApiMetaRoot(root, LangEnum.Typescript, dir.toFile());
        return Files.readString(dir.resolve("test-dto.ts"), StandardCharsets.UTF_8);
    }

    /** Index of the {@code class|interface|enum <name>} declaration in generated output. */
    private static int declIndex(String content, String name) {
        Matcher m = Pattern.compile("(?:class|interface|enum)\\s+" + Pattern.quote(name) + "\\b")
                .matcher(content);
        assertTrue(m.find(), () -> "declaration of " + name + " not found in:\n" + content);
        return m.start();
    }

    // ---- tests ---------------------------------------------------------------

    @Test
    void referencerAlphabeticallyBeforeReferenced_stillEmitsReferencedFirst(@TempDir Path dir)
            throws IOException {
        // `Apple` references `Zebra`; alphabetical order (GENDET-001) would put Apple first → TDZ.
        Dto zebra = dto("Zebra", field("id", type("String")));
        Dto apple = dto("Apple", field("z", type("Zebra")));
        String ts = genTs(dir, apple, zebra); // input order is referencer-first, on purpose
        assertTrue(declIndex(ts, "Zebra") < declIndex(ts, "Apple"),
                () -> "Zebra must be declared before Apple:\n" + ts);
    }

    @Test
    void genericArgumentEdge_listOfReferenced(@TempDir Path dir) throws IOException {
        Dto zebra = dto("Zebra", field("id", type("String")));
        Dto apple = dto("Apple", field("zs", generic("List", 1, type("Zebra"))));
        String ts = genTs(dir, apple, zebra);
        assertTrue(declIndex(ts, "Zebra") < declIndex(ts, "Apple"),
                () -> "Zebra (List<Zebra> arg) must be declared before Apple:\n" + ts);
    }

    @Test
    void nestedGenericArgumentEdge_mapOfListOfReferenced(@TempDir Path dir) throws IOException {
        Dto zebra = dto("Zebra", field("id", type("String")));
        Dto apple = dto("Apple",
                field("m", generic("Map", 2, type("String"), generic("List", 1, type("Zebra")))));
        String ts = genTs(dir, apple, zebra);
        assertTrue(declIndex(ts, "Zebra") < declIndex(ts, "Apple"),
                () -> "Zebra (Map<String,List<Zebra>> arg) must be declared before Apple:\n" + ts);
    }

    @Test
    void noExtendsClauseEmittedForDtos(@TempDir Path dir) throws IOException {
        // Inheritance is flattened upstream — the DTO file must never emit a superclass edge.
        Dto zebra = dto("Zebra", field("id", type("String")));
        Dto apple = dto("Apple", field("z", type("Zebra")));
        String ts = genTs(dir, apple, zebra);
        assertTrue(!ts.contains("extends"),
                () -> "DTO output must not emit an extends clause:\n" + ts);
    }

    @Test
    void cycle_generationSucceedsDeterministicallyAndWarns(@TempDir Path dir, @TempDir Path dir2)
            throws IOException {
        Logger genLogger = (Logger) LoggerFactory.getLogger(Gen.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        genLogger.addAppender(appender);

        String ts1;
        String ts2;
        try {
            ts1 = genTs(dir, dto("Alpha", field("b", type("Beta"))),
                    dto("Beta", field("a", type("Alpha"))));
            ts2 = genTs(dir2, dto("Alpha", field("b", type("Beta"))),
                    dto("Beta", field("a", type("Alpha"))));
        } finally {
            genLogger.detachAppender(appender);
        }

        // Generation succeeds and emits both members.
        assertTrue(declIndex(ts1, "Alpha") >= 0);
        assertTrue(declIndex(ts1, "Beta") >= 0);
        // Cycle emission is deterministic across runs.
        assertEquals(ts1, ts2);
        // A WARN names the cycle participants.
        boolean warned = appender.list.stream().anyMatch(e ->
                e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("cycle")
                        && e.getFormattedMessage().contains("Alpha")
                        && e.getFormattedMessage().contains("Beta"));
        assertTrue(warned, () -> "expected a WARN naming the cycle participants; got: "
                + appender.list);
    }

    @Test
    void determinism_byteIdenticalAcrossShuffledInput(@TempDir Path d1, @TempDir Path d2)
            throws IOException {
        // Same graph, DIFFERENT input orderings (seeded shuffles). Byte-identical output proves
        // the sort — not the input order — determines emission. Same-order-twice would be vacuous.
        List<Dto> a = new ArrayList<>(List.of(sampleGraph()));
        Collections.shuffle(a, new Random(1));
        List<Dto> b = new ArrayList<>(List.of(sampleGraph()));
        Collections.shuffle(b, new Random(7));
        assertEquals(genTs(d1, a), genTs(d2, b),
                "generation must be byte-identical regardless of input DTO order");
    }

    @Test
    void independentNodes_emittedInAlphabeticalTieBreakOrder(@TempDir Path dir) throws IOException {
        // Two dependency-free nodes must fall back to the (name, originName) alphabetical order.
        String ts = genTs(dir, dto("Cherry", field("id", type("String"))),
                dto("Banana", field("id", type("String"))));
        assertTrue(declIndex(ts, "Banana") < declIndex(ts, "Cherry"),
                () -> "independent nodes must emit alphabetically:\n" + ts);
    }

    @Test
    void arrayElementEdge_concreteArrayType(@TempDir Path dir) throws IOException {
        // A concrete array field `Zebra[]` (a Class with isArray()==true upstream, NOT a
        // GenericArrayType) must still create an edge to its element type.
        Dto zebra = dto("Zebra", field("id", type("String")));
        Dto apple = dto("Apple", field("zs", type("Zebra[]")));
        String ts = genTs(dir, apple, zebra);
        assertTrue(declIndex(ts, "Zebra") < declIndex(ts, "Apple"),
                () -> "Zebra (Zebra[] element) must be declared before Apple:\n" + ts);
    }

    @Test
    void cycleWithDownstream_downstreamKeepsTopologicalPlacement(@TempDir Path dir)
            throws IOException {
        // M <-> N is a true cycle; Z references M (downstream of the cycle); A references Z
        // (further downstream). The naive "append the whole residual alphabetically" fallback
        // emits A,M,N,Z — violating A's dependency on Z. Only true cycle members (M,N) may take
        // the alphabetical fallback; Z and A must stay after their dependencies.
        Logger genLogger = (Logger) LoggerFactory.getLogger(Gen.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        genLogger.addAppender(appender);
        String ts;
        try {
            ts = genTs(dir,
                    dto("A", field("z", type("Z"))),
                    dto("Z", field("m", type("M"))),
                    dto("M", field("n", type("N"))),
                    dto("N", field("m", type("M"))));
        } finally {
            genLogger.detachAppender(appender);
        }
        // Downstream nodes keep topological placement (the blocking-bug assertion).
        assertTrue(declIndex(ts, "Z") < declIndex(ts, "A"),
                () -> "downstream A must be emitted after its dependency Z:\n" + ts);
        assertTrue(declIndex(ts, "M") < declIndex(ts, "Z"),
                () -> "downstream Z must be emitted after its dependency M:\n" + ts);
        // The WARN names ONLY the true cycle participants (M, N), not the downstream Z/A.
        var warn = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(msg -> msg.contains("cycle"))
                .findFirst();
        assertTrue(warn.isPresent(), () -> "expected a cycle WARN; got: " + appender.list);
        String participants = warn.get().substring(warn.get().lastIndexOf(':') + 1);
        assertTrue(participants.contains("M") && participants.contains("N"),
                () -> "WARN must name cycle members M and N: " + warn.get());
        assertTrue(!participants.contains("Z") && !participants.contains("A"),
                () -> "WARN must NOT name downstream Z/A as cycle members: " + warn.get());
    }

    private static Dto[] sampleGraph() {
        // Apple -> Zebra -> Mango, plus two independent nodes, to exercise the ordered ready-set.
        Dto mango = dto("Mango", field("id", type("String")));
        Dto zebra = dto("Zebra", field("m", generic("List", 1, type("Mango"))));
        Dto apple = dto("Apple", field("z", type("Zebra")));
        Dto banana = dto("Banana", field("id", type("String")));
        Dto cherry = dto("Cherry", field("id", type("String")));
        return new Dto[] {apple, zebra, mango, banana, cherry};
    }
}
