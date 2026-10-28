package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import tech.krpc.server.RpcServerBuilder.RpcMetaMethod;

/**
 * META-ARRAY-001 — DTO collection-type contract enforced at meta scan
 * ({@link RpcMetaServiceImpl#checkFieldContract} / {@code rejectUnsupportedArray}). SPEC §4
 * "Collection fields — use List&lt;T&gt;, not arrays": arrays are UNSUPPORTED (hard error);
 * single-dimension primitive arrays ({@code byte[]}) are the only exemption; multi-dimensional
 * ({@code int[][]}) and object arrays fail fast; {@code Map<K,V>} is NOT RECOMMENDED (a deduped
 * scan WARN, not an error). Backstop paths (method arg/return, nested generics, generic arrays)
 * are exercised through the real scan entries, not just direct field calls.
 */
class MetaArrayContractTest {

    static class Zebra {
        String name;
    }

    /** Object-array field — the unsupported shape the rule targets. */
    static class ObjectArrayDto {
        String id;
        Zebra[] zebras;
    }

    /** Object array of String — also unsupported. */
    static class StringArrayDto {
        String[] tags;
    }

    /** Multi-dimensional primitive array — UNSUPPORTED (componentType int[] is not primitive). */
    static class MultiDimArrayDto {
        int[][] grid;
    }

    /** Primitive arrays — the exempt binary/scalar-payload convention (cf. Img.img). */
    static class PrimitiveArrayDto {
        byte[] blob;
        int[] nums;
    }

    /** Nested object array via a generic — List&lt;Zebra[]&gt; reaches the getOrAdd raw-Class backstop. */
    static class NestedArrayDto {
        List<Zebra[]> boxes;
    }

    /** Generic array with a parameterized component — List&lt;List&lt;String&gt;[]&gt; reaches the
     *  getOrAdd GenericArrayType branch (component is a ParameterizedType, not a TypeVariable). */
    static class DeepGenericArrayDto {
        List<List<String>[]> deep;
    }

    /** Map field — NOT RECOMMENDED, tolerated with a WARN. */
    static class MapFieldDto {
        String id;
        Map<String, String> attrs;
    }

    /** Dedicated DTO so the dedup assertion owns the only scan of its Map field in the JVM. */
    static class MapDedupDto {
        Map<String, Long> counts;
    }

    /** The sanctioned collection shape — clean. */
    static class ListFieldDto {
        String id;
        List<Zebra> zebras;
    }

    // --- direct DTO field paths (checkFieldContract, rich DTO+field context) ---

    @Test
    void objectArrayField_failsFast_namingDtoAndField() {
        var ex = assertThrows(IllegalStateException.class,
                () -> RpcMetaServiceImpl.cls2dto(new HashMap<>(), ObjectArrayDto.class, 0, false));
        assertTrue(ex.getMessage().contains(ObjectArrayDto.class.getName()),
                "error must name the declaring DTO class: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("zebras"),
                "error must name the offending field: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("List<T>"),
                "error must point at the List<T> remedy: " + ex.getMessage());
    }

    @Test
    void stringArrayField_failsFast() {
        var ex = assertThrows(IllegalStateException.class,
                () -> RpcMetaServiceImpl.cls2dto(new HashMap<>(), StringArrayDto.class, 0, false));
        assertTrue(ex.getMessage().contains("tags"), ex.getMessage());
    }

    @Test
    void multiDimensionalPrimitiveArray_failsFast() {
        var ex = assertThrows(IllegalStateException.class,
                () -> RpcMetaServiceImpl.cls2dto(new HashMap<>(), MultiDimArrayDto.class, 0, false));
        assertTrue(ex.getMessage().contains("grid"), ex.getMessage());
        assertTrue(ex.getMessage().contains("multi-dimensional"), ex.getMessage());
    }

    @Test
    void singleDimensionPrimitiveArrayField_isExempt() {
        assertDoesNotThrow(
                () -> RpcMetaServiceImpl.cls2dto(new HashMap<>(), PrimitiveArrayDto.class, 0, false));
    }

    // --- backstop paths reached through the real scan, not direct field calls ---

    @Test
    void nestedObjectArrayInGeneric_failsFast_viaFieldScan() {
        // List<Zebra[]> — field type is List (passes checkFieldContract); the array is reached
        // when getOrAdd recurses into the ParameterizedType's Class argument (raw-Class backstop).
        var ex = assertThrows(IllegalStateException.class,
                () -> RpcMetaServiceImpl.cls2dto(new HashMap<>(), NestedArrayDto.class, 0, false));
        assertTrue(ex.getMessage().contains("Zebra[]"), ex.getMessage());
    }

    @Test
    void genericArrayWithConcreteComponent_failsFast_viaGenericArrayBranch() {
        // List<List<String>[]> — the inner List<String>[] is a GenericArrayType whose component is
        // a ParameterizedType (not a TypeVariable): must be rejected, not silently modeled List<T>.
        var ex = assertThrows(IllegalStateException.class,
                () -> RpcMetaServiceImpl.cls2dto(new HashMap<>(), DeepGenericArrayDto.class, 0, false));
        assertTrue(ex.getMessage().contains("List<") && ex.getMessage().contains("[]"),
                ex.getMessage());
    }

    @Test
    void methodArgumentArray_failsFast_viaApiScan() {
        var m = new RpcMetaMethod("ArgSvc", "save", Zebra[].class, null, "d", new Annotation[0]);
        var ex = assertThrows(IllegalStateException.class,
                () -> RpcServerBuilder.buildApiMeta(List.of(m)));
        assertTrue(ex.getMessage().contains("Zebra[]"), ex.getMessage());
    }

    @Test
    void methodReturnArray_failsFast_viaApiScan() {
        var m = new RpcMetaMethod("ResSvc", "list", null, Zebra[].class, "d", new Annotation[0]);
        var ex = assertThrows(IllegalStateException.class,
                () -> RpcServerBuilder.buildApiMeta(List.of(m)));
        assertTrue(ex.getMessage().contains("Zebra[]"), ex.getMessage());
    }

    // --- Map WARN + List clean (logback ListAppender; detach in finally) ---

    @Test
    void mapField_scanSucceeds_withWarn() {
        var appender = captureMetaLog();
        try {
            assertDoesNotThrow(
                    () -> RpcMetaServiceImpl.cls2dto(new HashMap<>(), MapFieldDto.class, 0, false));
            boolean warned = warnMessages(appender).stream()
                    .anyMatch(msg -> msg.contains("attrs") && msg.contains("Map"));
            assertTrue(warned, "a Map field must emit a WARN naming the field; events="
                    + warnMessages(appender));
        } finally {
            detachMetaLog(appender);
        }
    }

    @Test
    void mapWarn_isDedupedAcrossScans() {
        var appender = captureMetaLog();
        try {
            // MapDedupDto#counts is scanned nowhere else, so its first WARN happens here; a second
            // scan (as the server does for full/web/mcp metas) must NOT re-emit.
            RpcMetaServiceImpl.cls2dto(new HashMap<>(), MapDedupDto.class, 0, false);
            RpcMetaServiceImpl.cls2dto(new HashMap<>(), MapDedupDto.class, 0, false);
            long warns = warnMessages(appender).stream().filter(m -> m.contains("counts")).count();
            assertEquals(1L, warns, "Map WARN must be deduped per DTO field; events="
                    + warnMessages(appender));
        } finally {
            detachMetaLog(appender);
        }
    }

    @Test
    void listField_isClean_noWarn() {
        var appender = captureMetaLog();
        try {
            assertDoesNotThrow(
                    () -> RpcMetaServiceImpl.cls2dto(new HashMap<>(), ListFieldDto.class, 0, false));
            assertTrue(warnMessages(appender).isEmpty(), "a List<T> field must scan clean with no WARN");
        } finally {
            detachMetaLog(appender);
        }
    }

    private static ListAppender<ILoggingEvent> captureMetaLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(RpcMetaServiceImpl.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static List<String> warnMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static void detachMetaLog(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(RpcMetaServiceImpl.class)).detachAppender(appender);
    }
}
