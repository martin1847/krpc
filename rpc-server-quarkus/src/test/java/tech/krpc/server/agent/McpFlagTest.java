package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
 *       two parsers, nothing keeping them in step. Checked in two ways, because neither alone is
 *       enough: structurally, by decoding what {@code enabled()} and its immediate same-class helpers
 *       actually invoke (with the blind spots listed on that test), and behaviourally in a child JVM,
 *       because the symptom only appears in a process whose {@code KRPC_MCP} is really set.</li>
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
     * {@code McpFlag.enabled()} and resolves nothing on its own — or through a helper it calls.
     *
     * <p><b>Known blind spots (review R2 finding 3, accepted).</b> The walk follows exactly ONE level
     * of same-class helper, so a second parser reached through a second helper hop, through another
     * class, or through an {@code invokedynamic} target (lambda / method reference) is not seen: this
     * guard can be green while a hidden duplicate read exists. ADR-0003 leaves duplicate hand-written
     * reads to review until the read-site gate exists; this test narrows, but does not close, that
     * gap. Adding a second resolution point in {@code /mcp} is a review-blocking change regardless of
     * what this test says.
     */
    @Test
    void neitherMcpFaceResolvesTheFlagItself() {
        for (Class<?> face : List.of(McpHandler.class, McpGetHandler.class)) {
            Set<String> invoked = resolutionCallsOf(face);
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
     * The reader's own contract (review R2 finding 4): a WRONG instruction length is worse than an
     * unknown opcode, because the cursor lands mid-instruction and the walk silently under-reports
     * the calls this guard exists to find. {@code iinc} (0x84) and {@code if_icmpeq} (0x9F) were both
     * listed as one byte; both are three. In each code array below the operand byte is itself an
     * opcode with a longer encoding ({@code sipush}), which is exactly how a wrong length swallows
     * the following {@code invokestatic} instead of crashing.
     */
    @Test
    void theCodeReaderKeepsItsCursorOnMultiByteInstructions() {
        Object[] pool = syntheticPool();

        // iconst_0, iconst_0, if_icmpeq +17 (at offset 2), invokestatic #6 (at offset 5), return
        assertEquals(Set.of(PROBE_CALL), labels(invokedBy(new byte[] {
                0x03, 0x03, (byte) 0x9F, 0x00, 0x11, (byte) 0xB8, 0x00, 0x06, (byte) 0xB1}, pool)),
                "if_icmpeq is three bytes — at one byte the invoke that follows it disappears");

        // iinc 17, 0 (at offset 0), invokestatic #6 (at offset 3), return
        assertEquals(Set.of(PROBE_CALL), labels(invokedBy(new byte[] {
                (byte) 0x84, 0x11, 0x00, (byte) 0xB8, 0x00, 0x06, (byte) 0xB1}, pool)),
                "iinc is three bytes — at one byte the invoke that follows it disappears");
    }

    /** The fail-loud claim, made true: an opcode outside the table names itself. */
    @Test
    void anUnsupportedOpcodeNamesItselfInsteadOfUnderReporting() {
        // newarray (0xBC) is deliberately absent from the table: a gate method has no arrays.
        AssertionError failure = assertThrows(AssertionError.class,
                () -> invokedBy(new byte[] {(byte) 0xBC, 0x08, (byte) 0xB1}, syntheticPool()));
        assertTrue(failure.getMessage().contains("0xbc"),
                () -> "the failure must name the opcode it cannot decode: " + failure.getMessage());
    }

    private static final String PROBE_CALL = "tech/krpc/probe/Probe.gate";

    /** A hand-built constant pool whose entry #6 is {@code tech/krpc/probe/Probe.gate()Z}. */
    private static Object[] syntheticPool() {
        Object[] pool = new Object[7];
        pool[1] = "tech/krpc/probe/Probe";
        pool[2] = new int[] {1};                         // Class
        pool[3] = "gate";
        pool[4] = "()Z";
        pool[5] = new int[] {3, 4};                      // NameAndType
        pool[6] = new int[] {2, 5};                      // Methodref
        return pool;
    }

    private static Set<String> labels(List<Ref> refs) {
        return refs.stream().map(Ref::label).collect(Collectors.toSet());
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
        String output = runChild(EnabledProbe.class, envValue, 60);
        return output.lines()
                .filter(line -> line.startsWith(RESULT))
                .map(line -> line.substring(RESULT.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("probe printed no result: " + output));
    }

    /**
     * Runs {@code main} in a child JVM under a hard time budget (review R2 finding 5). The output is
     * drained on a separate thread, so neither a child that fills the pipe nor a child that keeps
     * stdout open can block the suite: the budget, not the pipe, decides when we give up — and then
     * the child is killed and the test fails.
     */
    private static String runChild(Class<?> main, String envValue, int budgetSeconds)
            throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                main.getName());
        builder.environment().put("KRPC_MCP", envValue);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        var output = new AtomicReference<>("");
        Thread drain = new Thread(() -> {
            try (InputStream out = process.getInputStream()) {
                output.set(new String(out.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException stopped) {
                // the child was killed or the pipe closed; the test below reports the timeout
            }
        }, main.getSimpleName() + "-drain");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!process.waitFor(budgetSeconds, TimeUnit.SECONDS)) {
                throw new AssertionError(main.getSimpleName() + " did not exit within "
                        + budgetSeconds + "s; killing it. Output so far: " + output.get());
            }
            drain.join(TimeUnit.SECONDS.toMillis(10));
            assertEquals(0, process.exitValue(), () -> "probe JVM failed: " + output.get());
            return output.get();
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * Review R2 finding 5: a child that never exits must fail this test, not hang CI. Reading the
     * child's output before waiting for it — the previous shape — blocked here forever, because
     * {@link HangingProbe} keeps stdout open.
     */
    @Test
    void aChildProbeThatNeverExitsFailsWithinItsBudget() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> runChild(HangingProbe.class, "true", 2));
        assertTrue(failure.getMessage().contains("did not exit within 2s"),
                () -> "expected the budget to expire, got: " + failure.getMessage());
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

    /** Child-JVM body that holds stdout open and never exits — the CI hang the budget must cut. */
    public static final class HangingProbe {
        public static void main(String[] args) throws InterruptedException {
            System.out.println("STARTED");               // stdout stays open on purpose
            Thread.sleep(TimeUnit.MINUTES.toMillis(10));
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

    /** One invoked method, as the constant pool names it. */
    private record Ref(String owner, String name, String descriptor) {
        String label() {
            return owner + "." + name;
        }
    }

    /** A parsed class file: constant pool plus the bytecode of every method it declares. */
    private record Parsed(Object[] pool, Map<String, byte[]> bodies) {
        byte[] body(String name, String descriptor) {
            return bodies.get(name + descriptor);
        }
    }

    /**
     * Everything {@code face.enabled()} invokes, plus everything invoked by the same-class helpers it
     * calls directly — ONE level of indirection (review R2 finding 3), which is the shape a split
     * implementation reaches for first: keep calling the shared accessor, then decide with a private
     * {@code localEnabled()} that reads the environment itself.
     */
    private static Set<String> resolutionCallsOf(Class<?> face) {
        Parsed parsed = parse(face);
        String owner = face.getName().replace('.', '/');
        byte[] gate = parsed.body("enabled", "()Z");
        assertNotNull(gate, () -> face.getSimpleName() + ".enabled()Z has no Code attribute");

        Set<String> calls = new LinkedHashSet<>();
        for (Ref call : invokedBy(gate, parsed.pool())) {
            calls.add(call.label());
            if (!owner.equals(call.owner())) {
                continue;
            }
            byte[] helper = parsed.body(call.name(), call.descriptor());
            if (helper == null) {
                continue;
            }
            for (Ref nested : invokedBy(helper, parsed.pool())) {
                calls.add(nested.label());
            }
        }
        return calls;
    }

    /** Constant pool + method bodies of one compiled class. */
    private static Parsed parse(Class<?> type) {
        var in = new DataStream(classBytes(type));
        in.skip(8);                                     // magic + minor + major
        Object[] pool = readConstantPool(in);
        in.skip(2 + 2 + 2);                             // access, this_class, super_class
        in.skip(2 * in.u2());                           // interfaces
        skipMembers(in);                                // fields
        Map<String, byte[]> bodies = new HashMap<>();
        int methods = in.u2();
        for (int i = 0; i < methods; i++) {
            in.skip(2);                                 // access flags
            String name = utf8(pool, in.u2());
            String descriptor = utf8(pool, in.u2());
            byte[] code = readCode(in, pool);
            if (code != null) {
                bodies.put(name + descriptor, code);
            }
        }
        return new Parsed(pool, bodies);
    }

    /**
     * Walks the invoke opcodes of one code array. Instruction lengths are JVMS §6.5 lengths for the
     * subset a boolean gate method can contain; an opcode outside that subset fails loudly and names
     * itself rather than being guessed at, so this cannot silently degrade into "found no calls"
     * (which would be a vacuous green). A WRONG length would do the same damage more quietly — see
     * {@link #theCodeReaderKeepsItsCursorOnMultiByteInstructions()}, which pins the two that were
     * wrong (review R2 finding 4).
     *
     * <p>Blind spot, on purpose: {@code invokedynamic} is length-skipped but its bootstrap target is
     * not resolved, so a resolution hidden in a lambda body or method reference is invisible here.
     */
    private static List<Ref> invokedBy(byte[] code, Object[] pool) {
        List<Ref> invoked = new ArrayList<>();
        int pc = 0;
        while (pc < code.length) {
            int opcode = code[pc] & 0xFF;
            Integer length = INSTRUCTION_LENGTH.get(opcode);
            int offset = pc;
            assertNotNull(length, () -> String.format(
                    "unsupported opcode 0x%02x at offset %d: this reader must not guess an"
                    + " instruction length, or it would under-report the calls it exists to find",
                    opcode, offset));
            if (opcode == 0xB6 || opcode == 0xB7 || opcode == 0xB8 || opcode == 0xB9) {
                invoked.add(ref(pool, ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF)));
            }
            pc += length;
        }
        return invoked;
    }

    /**
     * Opcode -> instruction length (JVMS §6.5), for the subset a boolean gate method and its
     * immediate helpers can contain — locals, comparisons, branches, invokes. A method using anything
     * else (a switch, a wide index, an array store) is not a plain flag gate: the lookup fails and
     * names the opcode instead of under-reporting the method's calls.
     */
    private static final Map<Integer, Integer> INSTRUCTION_LENGTH = instructionLengths();

    private static Map<Integer, Integer> instructionLengths() {
        Map<Integer, Integer> lengths = new HashMap<>();
        for (int opcode : new int[] {
                0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D,
                0x1A, 0x1B, 0x1C, 0x1D, 0x2A, 0x2B, 0x2C, 0x2D, 0x3B, 0x3C, 0x3D, 0x3E, 0x4B, 0x4C,
                0x4D, 0x4E, 0x57, 0x58, 0x59, 0x60, 0x64, 0x82, 0x83, 0xAC, 0xB0, 0xB1, 0xBE,
                0xBF}) {
            lengths.put(opcode, 1);
        }
        for (int opcode : new int[] {0x10, 0x12, 0x15, 0x19, 0x36, 0x3A}) {
            lengths.put(opcode, 2);
        }
        for (int opcode : new int[] {
                0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2,
                0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD,
                0xC0, 0xC1, 0xC6, 0xC7}) {
            lengths.put(opcode, 3);                     // includes iinc (0x84) and if_icmpeq (0x9F)
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

    /** Owner, name and descriptor of a Methodref / InterfaceMethodref entry. */
    private static Ref ref(Object[] pool, int index) {
        int[] entry = (int[]) pool[index];
        String owner = utf8(pool, ((int[]) pool[entry[0]])[0]);
        int[] nameAndType = (int[]) pool[entry[1]];
        return new Ref(owner, utf8(pool, nameAndType[0]), utf8(pool, nameAndType[1]));
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
