package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import tech.krpc.util.FlagResolution.Source;

/**
 * ADR-0003 (umbrella) known debt 3 — the hand-written half of {@code KRPC_MCP} resolves through ONE
 * accessor, with requirement 1/2 value semantics.
 *
 * <p>Two things are pinned here, and they fail for different reasons:
 * <ul>
 *   <li><b>The value matrix</b> ({@link McpFlag#resolve}/{@link McpFlag#read}) — {@code " true "} out
 *       of a YAML block scalar or a Dockerfile line continuation is ON (it used to be silently OFF),
 *       an unrecognised or unreadable value keeps the capability unexposed (class B safe side).</li>
 *   <li><b>The desync guard</b> — neither {@code /mcp} face may read or parse the environment itself.
 *       This is the failure ADR-0003 records as the third instance of the split switch: two handlers,
 *       two parsers, nothing keeping them in step. Checked structurally (the compiled handler must
 *       reference {@code McpFlag} and must NOT reference an env read), because the behavioural
 *       symptom only appears in a process whose {@code KRPC_MCP} is actually set — that is the job of
 *       the end-to-end status-code matrix, which cannot run inside this JVM.</li>
 * </ul>
 */
class McpFlagTest {

    /** Requirement 1's ON side — including the whitespace form that used to resolve OFF. */
    @Test
    void recognisedTruthyEnvEnablesMcp_trimmedAndCaseInsensitive() {
        for (String raw : new String[] {"true", "TRUE", " true ", "\ttrue\n", "1", " 1 "}) {
            var resolution = McpFlag.resolve(raw);
            assertTrue(resolution.value(), () -> "KRPC_MCP=\"" + raw + "\" must enable /mcp");
            assertEquals(Source.ENV, resolution.source());
        }
    }

    /** Class B default: nothing configured means the capability is not exposed (NS-6). */
    @Test
    void unconfiguredEnvKeepsMcpOff_byDefaultNotByAccident() {
        for (String raw : new String[] {null, "", "   ", "\n"}) {
            var resolution = McpFlag.resolve(raw);
            assertFalse(resolution.value(), "an unconfigured KRPC_MCP must leave /mcp absent");
            assertEquals(Source.DEFAULT, resolution.source(),
                    "blank is 'not configured', so the log must say the default stood");
        }
    }

    @Test
    void explicitFalseOrZeroKeepsMcpOff() {
        for (String raw : new String[] {"false", "FALSE", " false ", "0", " 0 "}) {
            var resolution = McpFlag.resolve(raw);
            assertFalse(resolution.value(), () -> "KRPC_MCP=\"" + raw + "\" must keep /mcp off");
            assertEquals(Source.ENV, resolution.source());
        }
    }

    /** Requirement 2: for a class-B capability the safe side is staying unexposed. */
    @Test
    void unrecognisedEnvKeepsMcpOff_andSaysWhat() {
        for (String raw : new String[] {"yes", "ture", "on", "enabled", "2"}) {
            var resolution = McpFlag.resolve(raw);
            assertFalse(resolution.value(),
                    () -> "KRPC_MCP=\"" + raw + "\" must not open /mcp on a guess");
            assertEquals(Source.UNRECOGNIZED, resolution.source());
            assertEquals("unrecognized(" + raw + ")", resolution.describe(),
                    "an operator who mistyped the opt-in must find the reason in the log");
        }
    }

    /**
     * Requirement 2: the lookup is guarded. Driven with the name {@code System.getenv} rejects
     * ({@code null} -> {@link NullPointerException}), which exercises the real {@code catch} in
     * {@link McpFlag#read} — the old {@code EnvUtils.env} call let a failure propagate out of a
     * handler's {@code enabled()} during route registration.
     */
    @Test
    void lookupFailureKeepsMcpOffInsteadOfPropagating() {
        var resolution = McpFlag.read(null);
        assertFalse(resolution.value(), "a failed read must leave the capability unexposed");
        assertEquals(Source.READ_FAILURE, resolution.source());
        assertEquals("read-failure(NullPointerException)", resolution.describe());
    }

    /** The env half is the accessor's own business: no property source is read here (see ADR-0003). */
    @Test
    void theEnvHalfNeverReadsThePropertyHalf() {
        assumeTrue(System.getenv(McpFlag.ENV_ENABLED) == null,
                "KRPC_MCP is set in this environment; the property-isolation check is moot");
        System.setProperty("rpc.server.mcp.enabled", "true");
        try {
            assertFalse(McpFlag.read(McpFlag.ENV_ENABLED).value(),
                    "rpc.server.mcp.enabled is the container-injected half — the hand-written"
                    + " accessor must not resolve it a second time");
        } finally {
            System.clearProperty("rpc.server.mcp.enabled");
        }
    }

    /**
     * ADR-0003 requirement 4 for the two faces of {@code /mcp}: one accessor, no local parsing —
     * checked at METHOD level (review R1 finding 2). The earlier version of this guard only asked
     * whether the handler CLASS mentioned {@code McpFlag} anywhere, which an unused helper elsewhere
     * in the class would have satisfied. What must hold is that {@code enabled()} itself invokes
     * {@code McpFlag.enabled()} and resolves nothing on its own.
     */
    @Test
    void neitherMcpFaceResolvesTheFlagItself() {
        for (Class<?> face : List.of(McpHandler.class, McpGetHandler.class)) {
            Set<String> invoked = methodsInvokedBy(face, "enabled", "()Z");
            assertTrue(invoked.contains("tech/krpc/server/agent/McpFlag.enabled"),
                    () -> face.getSimpleName() + ".enabled() must invoke the shared McpFlag accessor;"
                          + " it invokes " + invoked);
            assertFalse(invoked.contains("tech/krpc/util/EnvUtils.env"),
                    () -> face.getSimpleName() + ".enabled() must not read the environment itself"
                          + " (ADR-0003 known debt 3: that is what desynchronised POST from GET)");
            assertFalse(invoked.contains("java/lang/System.getenv"),
                    () -> face.getSimpleName() + ".enabled() must not call System.getenv itself");
            assertFalse(invoked.contains("java/lang/System.getProperty"),
                    () -> face.getSimpleName() + ".enabled() must not read a property itself");
        }
    }

    /**
     * The input branch the structural guard cannot see and the rest of this JVM cannot reach: the
     * documented env opt-in is ON (review R1 finding 2). {@code KRPC_MCP} can only be set for a fresh
     * process, and publishing a value into the shared accessor from here would leak into every other
     * test in this JVM — so both faces are asked in a child JVM instead.
     *
     * <p>This is the in-suite regression gate for the desync the one-off E2E matrix showed: a face
     * that parses {@code KRPC_MCP} itself (untrimmed), or that keeps calling the accessor but discards
     * its answer ({@code mcpEnabled || (McpFlag.enabled() && false)}), disagrees with the other face
     * here. The second case, garbage input, is the non-vacuity control: it proves this probe follows
     * the environment instead of reporting a constant.
     */
    @Test
    void bothMcpFacesAgreeWhenTheEnvOptInIsOn() throws Exception {
        // Whitespace on purpose: the trimmed form is the approved behaviour change, and an untrimmed
        // local parser in one face shows up as a split here.
        String on = probe(" true ");
        assertEquals("post=true get=true", on,
                "with KRPC_MCP=\" true \" both /mcp faces must be ON, through the same accessor");

        String garbage = probe("fasle");
        assertEquals("post=false get=false", garbage,
                "non-vacuity + class B safe side: an unrecognised opt-in leaves both faces OFF");
    }

    /** Runs {@link EnabledProbe} in a child JVM with {@code KRPC_MCP} set to {@code envValue}. */
    private static String probe(String envValue) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                EnabledProbe.class.getName());
        builder.environment().put("KRPC_MCP", envValue);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), () -> "probe JVM hung: " + output);
        assertEquals(0, process.exitValue(), () -> "probe JVM failed: " + output);
        return output.lines()
                .filter(line -> line.startsWith(RESULT))
                .map(line -> line.substring(RESULT.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("probe printed no result: " + output));
    }

    private static final String RESULT = "RESULT ";

    /** Child-JVM body: asks both faces, with the injected config half OFF, what the env half says. */
    public static final class EnabledProbe {
        public static void main(String[] args) {
            McpHandler post = new McpHandler();
            McpGetHandler get = new McpGetHandler();
            post.mcpEnabled = false;
            get.mcpEnabled = false;
            System.out.println(RESULT + "post=" + post.enabled() + " get=" + get.enabled());
        }
    }

    /** Non-vacuity for the structural guard: both faces agree on the injected-config branch too. */
    @Test
    void bothMcpFacesAgreeOnTheInjectedConfigBranch() {
        McpHandler post = new McpHandler();
        McpGetHandler get = new McpGetHandler();

        post.mcpEnabled = false;
        get.mcpEnabled = false;
        assertEquals(post.enabled(), get.enabled(),
                "POST /mcp and GET /mcp must never disagree about whether the endpoint exists");

        post.mcpEnabled = true;
        get.mcpEnabled = true;
        assertTrue(post.enabled(), "the injected property still opens POST /mcp");
        assertTrue(get.enabled(), "the injected property still opens GET /mcp");
    }

    // --- minimal class-file reader ------------------------------------------------------------
    // Enough of JVMS §4 to answer "what does THIS method invoke": constant pool, then the method's
    // Code attribute, then the invoke opcodes inside it. Deliberately hand-rolled: java.lang.classfile
    // needs source level 24 and this module compiles at 21, and pulling in ASM for one test would add
    // a dependency to a published module's test classpath.

    /** {@code owner/Name.method} for every method the given method body invokes. */
    private static Set<String> methodsInvokedBy(Class<?> type, String method, String descriptor) {
        byte[] bytes = classBytes(type);
        var in = new DataStream(bytes);
        in.skip(8);                                     // magic + minor + major
        Object[] pool = readConstantPool(in);
        in.skip(2 + 2 + 2);                             // access, this_class, super_class
        in.skip(2 * in.u2());                           // interfaces
        skipMembers(in);                                // fields
        int methods = in.u2();
        for (int i = 0; i < methods; i++) {
            in.skip(2);                                 // access flags
            String name = utf8(pool, in.u2());
            String desc = utf8(pool, in.u2());
            byte[] code = readCode(in, pool);
            if (method.equals(name) && descriptor.equals(desc)) {
                assertNotNull(code, () -> type.getSimpleName() + "." + name + " has no Code");
                return invokedBy(code, pool);
            }
        }
        throw new AssertionError("no method " + method + descriptor + " in " + type.getName());
    }

    /**
     * Walks the invoke opcodes of one code array. Instruction lengths are decoded for the opcodes
     * javac emits in these gate methods; an unknown opcode fails loudly rather than guessing, so this
     * can never silently degrade into "found no calls" (which would be a vacuous green).
     */
    private static Set<String> invokedBy(byte[] code, Object[] pool) {
        Set<String> invoked = new HashSet<>();
        int pc = 0;
        while (pc < code.length) {
            int opcode = code[pc] & 0xFF;
            int length = INSTRUCTION_LENGTH.get(opcode);
            if (opcode == 0xB6 || opcode == 0xB7 || opcode == 0xB8 || opcode == 0xB9) {
                invoked.add(methodRef(pool, ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF)));
            }
            pc += length;
        }
        return invoked;
    }

    /**
     * Opcode -> instruction length, for the subset a boolean gate method can contain. A method using
     * anything else (a switch, a wide index, an array store) is not a plain flag gate: the lookup
     * throws, and the test fails with the offending opcode instead of under-reporting its calls.
     */
    private static final Map<Integer, Integer> INSTRUCTION_LENGTH = instructionLengths();

    private static Map<Integer, Integer> instructionLengths() {
        Map<Integer, Integer> lengths = new HashMap<>();
        for (int opcode : new int[] {
                0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D,
                0x1A, 0x1B, 0x1C, 0x1D, 0x2A, 0x2B, 0x2C, 0x2D, 0x3B, 0x3C, 0x3D, 0x3E, 0x57, 0x58,
                0x59, 0x60, 0x64, 0x82, 0x83, 0x84, 0x9F, 0xAC, 0xB0, 0xB1, 0xBE, 0xBF}) {
            lengths.put(opcode, 1);
        }
        for (int opcode : new int[] {0x10, 0x12, 0x15, 0x36, 0x3A, 0x19}) {
            lengths.put(opcode, 2);
        }
        for (int opcode : new int[] {
                0x11, 0x13, 0x14, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4,
                0xA5, 0xA6, 0xA7, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1}) {
            lengths.put(opcode, 3);
        }
        lengths.put(0xB9, 5);                           // invokeinterface
        lengths.put(0xBA, 5);                           // invokedynamic
        lengths.put(0xC5, 4);                           // multianewarray
        return Map.copyOf(lengths);
    }

    private static byte[] classBytes(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, () -> "cannot read compiled class " + resource);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError("cannot read compiled class " + resource, e);
        }
    }

    /** Constant pool entries; method/field refs keep their two indices for later resolution. */
    private static Object[] readConstantPool(DataStream in) {
        int count = in.u2();
        Object[] pool = new Object[count];
        for (int i = 1; i < count; i++) {
            int tag = in.u1();
            switch (tag) {
                case 1 -> pool[i] = in.utf8();
                case 7, 8, 16, 19, 20 -> pool[i] = new int[] {in.u2()};
                case 15 -> pool[i] = new int[] {in.u1(), in.u2()};
                case 3, 4, 9, 10, 11, 12, 17, 18 -> pool[i] = new int[] {in.u2(), in.u2()};
                case 5, 6 -> {
                    pool[i] = new int[] {in.u2(), in.u2(), in.u2(), in.u2()};
                    i++;                                // long/double take two slots
                }
                default -> throw new AssertionError("unknown constant pool tag " + tag);
            }
        }
        return pool;
    }

    private static String utf8(Object[] pool, int index) {
        return (String) pool[index];
    }

    /** {@code owner/Name.method} for a Methodref / InterfaceMethodref entry. */
    private static String methodRef(Object[] pool, int index) {
        int[] ref = (int[]) pool[index];
        String owner = utf8(pool, ((int[]) pool[ref[0]])[0]);
        int[] nameAndType = (int[]) pool[ref[1]];
        return owner + "." + utf8(pool, nameAndType[0]);
    }

    private static void skipMembers(DataStream in) {
        int count = in.u2();
        for (int i = 0; i < count; i++) {
            in.skip(2 + 2 + 2);
            skipAttributes(in);
        }
    }

    private static void skipAttributes(DataStream in) {
        int count = in.u2();
        for (int i = 0; i < count; i++) {
            in.skip(2);
            in.skip(in.u4());
        }
    }

    /** Returns the method's bytecode, or {@code null} for an abstract/native method. */
    private static byte[] readCode(DataStream in, Object[] pool) {
        byte[] code = null;
        int attributes = in.u2();
        for (int i = 0; i < attributes; i++) {
            String name = utf8(pool, in.u2());
            int length = in.u4();
            if (!"Code".equals(name)) {
                in.skip(length);
                continue;
            }
            in.skip(2 + 2);                             // max_stack, max_locals
            code = in.bytes(in.u4());
            in.skip(length - 8 - code.length);          // exception table + nested attributes
        }
        return code;
    }

    /** Big-endian cursor over the class file. */
    private static final class DataStream {
        private final byte[] data;
        private int offset;

        DataStream(byte[] data) {
            this.data = data;
        }

        int u1() {
            return data[offset++] & 0xFF;
        }

        int u2() {
            return (u1() << 8) | u1();
        }

        int u4() {
            return (u2() << 16) | u2();
        }

        void skip(int count) {
            offset += count;
        }

        byte[] bytes(int count) {
            byte[] out = java.util.Arrays.copyOfRange(data, offset, offset + count);
            offset += count;
            return out;
        }

        String utf8() {
            return new String(bytes(u2()), StandardCharsets.UTF_8);
        }
    }
}
