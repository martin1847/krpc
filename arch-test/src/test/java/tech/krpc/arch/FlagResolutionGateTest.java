package tech.krpc.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaStaticInitializer;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ADR-0003 (umbrella) — kill switches must be resolved LAZILY on the first-use path, never in a
 * static initializer.
 *
 * <p><b>Why this is a build-failing gate.</b> GraalVM/Quarkus native builds run
 * {@code initializeAtBuildTime} over our packages: whatever a {@code <clinit>} reads from the
 * environment is baked into the image and can never be changed at runtime — the switch stops
 * switching. This was observed on a real image. NORTH_STAR's meta-rule ("a structural invariant is
 * only real once a machine checks it") means the principle does not exist until it is a gate.
 *
 * <p><b>The gate covers two layers</b> — checking only the first one gives a false green:
 * <ol>
 *   <li><b>Direct read.</b> A {@code <clinit>} (static field initializer expressions and
 *       {@code static} blocks both compile into it) calls {@code System.getProperty} /
 *       {@code System.getenv} / {@code System.getProperties}.</li>
 *   <li><b>Indirect capture.</b> A {@code <clinit>} calls a <em>flag accessor</em> — a method that
 *       hands back an already-resolved flag. Fixing the accessor to read lazily does NOT fix the
 *       caller: {@code static final boolean OTEL_ENABLED = KrpcOtel.enabled();} freezes at build
 *       time either way.</li>
 * </ol>
 *
 * <p><b>How "flag accessor" is derived</b> (no hand-maintained list — a list rots silently). Least
 * fixed point over the imported production classes of:
 * <ul>
 *   <li>(a) a code unit that itself calls {@code System.getenv/getProperty/getProperties};</li>
 *   <li>(b) a code unit that calls a flag accessor (caller closure — covers wrapper methods and
 *       constructors, e.g. {@code static final Foo F = new Foo();} where the ctor reads env);</li>
 *   <li>(c) a code unit that reads a <em>flag field</em>: a static field of scalar type
 *       (primitive / box / String) assigned by a {@code <clinit>} that is itself a flag resolver.
 *       This is the rule that makes {@code KrpcOtel.enabled()} — a plain getter over
 *       {@code private static final boolean ENABLED} — a known accessor without naming it.</li>
 * </ul>
 * (c) feeds (b) and (b) feeds (c), so the three are iterated to a fixed point.
 *
 * <p><b>Known false negatives</b> (ArchUnit sees bytecode structure, not data flow — stated here
 * rather than papered over):
 * <ul>
 *   <li><b>Virtual/interface dispatch.</b> Call targets are the statically declared member. A
 *       {@code <clinit>} calling {@code SomeInterface.flag()} is not matched to the implementation
 *       that reads env.</li>
 *   <li><b>Reflection / MethodHandles / service loading</b> into an env reader.</li>
 *   <li><b>Non-scalar flag carriers.</b> A flag captured into a static field of a non-scalar type
 *       (a record, {@code Optional}, a config holder object) is not treated as a flag field, so its
 *       getters are not derived as accessors. Widening the type filter was rejected: a
 *       {@code <clinit>} typically also assigns unrelated non-scalar constants, which would make
 *       most getters on that class "accessors" and produce false REDs.</li>
 *   <li><b>Modules outside the scan face — this is NOT full-repo coverage.</b> The analysis subject
 *       is ADR-0005's {@link ArchitectureTest.OnlyCoreModules}: {@code rpc-api}, {@code rpc-common},
 *       {@code rpc-client}, {@code rpc-server}, {@code rpc-server-quarkus}, {@code http-server}.
 *       Every OTHER first-party module — the Spring adapters ({@code rpc-client-spring},
 *       {@code rpc-server-spring}), {@code ext-rpc-gen}, examples, test fixtures — is
 *       <b>not checked at all</b>: a build-time-baked flag there passes unseen. This is a scan-face
 *       gap, distinct from the third-party gap below. Widening the scan face is an ADR-0005
 *       decision (its EXCLUDED allowlist and the adapter policy-review trigger), not something to
 *       change here unilaterally.</li>
 *   <li><b>Third-party accessors.</b> Even within the scan face, only classes owned by this repo
 *       are in the call graph, so a flag resolved inside a dependency and exposed by it is
 *       invisible.</li>
 *   <li><b>Env reads that are not {@code java.lang.System}</b> — e.g. MicroProfile
 *       {@code ConfigProvider}, {@code Dotenv}, direct {@code /proc} reads. The seed set is
 *       deliberately narrow to keep the derivation precise.</li>
 * </ul>
 *
 * <p><b>Deliberate widenings</b> (stricter than the minimum, both fail RED so they are safe):
 * <ul>
 *   <li>The rule fires on <em>calling</em> a flag accessor from a {@code <clinit>}, without proving
 *       the result is stored into a static field. Proving the store is data flow, which ArchUnit
 *       cannot do; and a flag resolution in a {@code <clinit>} whose result is discarded is dead
 *       code, not a legitimate pattern.</li>
 *   <li>Reading a flag field of ANOTHER class from a {@code <clinit>} is also a violation
 *       ({@code static final boolean X = Other.FLAG;} freezes exactly the same way).</li>
 * </ul>
 *
 * <p><b>Lambdas are excluded everywhere.</b> javac attributes a lambda body's accesses to the
 * enclosing code unit, so {@code static final Supplier<Boolean> S = KrpcOtel::enabled;} or
 * {@code () -> System.getenv(x)} would look like a {@code <clinit>} read. Those are exactly the
 * LAZY form this ADR asks for; counting them would be a false RED.
 *
 * <p><b>Frozen baseline.</b> Per ADR-0005 the pre-existing violations are grandfathered in
 * {@code arch-test/archunit_store/}; any NEW one fails the build and the baseline only shrinks.
 * It started at six lines in TWO different categories — do not treat them alike — and is now
 * <b>three</b>: category (1) has been repaid, category (2) is still open.
 *
 * <p><b>(1) Real debt — 3 lines, REPAID (KRPC-FLAG-001, umbrella ADR-0003 known debts 1 and 2);
 * the baseline shrank by exactly these:</b>
 * <ul>
 *   <li>{@code KrpcOtel.ENABLED} ({@code KrpcOtel.java:62}, 2 lines: the property read and the env
 *       read) — the OTEL kill switch, layer 1. The switch this gate exists for. Now resolved on the
 *       first {@code enabled()} call and memoised behind a guard, so no {@code <clinit>} touches the
 *       environment.</li>
 *   <li>{@code AbstractHttpHandler.OTEL_ENABLED} ({@code AbstractHttpHandler.java:88}) — layer 2:
 *       captured {@code KrpcOtel.enabled()} into a static field, which would have stayed frozen even
 *       after {@code KrpcOtel} was made lazy (that is why both had to be fixed together). The
 *       handler now calls the accessor per request.</li>
 * </ul>
 * (An earlier revision of this paragraph said "4 lines" while listing these same three; the store
 * has always held three such entries. The count was the error, not the list.)
 *
 * <p>Layer 2 keeps its teeth only because the accessor's read stays statically reachable from
 * {@code enabled()} (a direct {@code System} read in the accessor's own body). A {@code Supplier},
 * method reference or lambda passed in as "the resolver" would hide it: the read then sits behind
 * virtual dispatch or in a lambda body, both of which the derivation below skips by design, so
 * {@code KrpcOtel.enabled()} would stop being derived as a flag accessor and layer 2 would silently
 * match nothing — which the anti-vacuous-green guard does NOT cover (it guards layer 1's seed set
 * only). Keep the read in the accessor's body for that reason.
 *
 * <p>A holder class is a different case and is NOT a blind spot: its {@code <clinit>} does the read,
 * so it is reported here directly (layer 1 or 2, depending on shape), and the accessor that hands the
 * holder's field on is still derived through rule (c). It is forbidden because build-time class
 * initialization bakes it, not because it evades this gate — measured, not assumed: a static memo
 * inside {@code McpFlag} was probed during KRPC-FLAG-001 and this rule reported
 * {@code calls flag accessor <McpFlag.read(String)>}.
 *
 * <p><b>(2) Undecided — the 3 lines that remain; DO NOT "fix" these:</b>
 * {@code RpcConstants.CI_BUILD_ID} (layer 1) is <em>not a flag</em>. Its source comment
 * ("利用graalVM特性，缓存构建信息") says the build-time bake is the intended behaviour: build
 * metadata stamped into the native image. The two {@code RpcServiceExpose} lines are collateral —
 * its {@code static} block reads that already-baked constant.
 * <ul>
 *   <li>Freezing here records that the disposition is <b>UNDECIDED</b>; it does not classify these
 *       as debt, and it does not accept them either.</li>
 *   <li>Per umbrella ADR-0003 this is an explicit <b>open question</b> awaiting an owner decision.
 *       The likely outcome is an explicit exemption mechanism (an allowlist or an annotation
 *       recognised by this gate), <b>not</b> lazy resolution — making {@code CI_BUILD_ID} lazy
 *       would defeat its purpose and is exactly the wrong direction.</li>
 *   <li><b>Nobody may change {@code CI_BUILD_ID} (or the {@code RpcServiceExpose} block) on the
 *       strength of this gate before that decision is made.</b> A follow-up must take it to the
 *       owner via ADR-0003, not silently refactor it.</li>
 *   <li>The alternative — a silent carve-out inside this rule — was rejected: freezing keeps the
 *       open question visible in the baseline until it is answered. ADR-0003 records and endorses
 *       this handling.</li>
 * </ul>
 *
 * <p>The baseline annotation lives here rather than inside the store file because ArchUnit treats
 * every store line as a violation record: a comment line reads back as an obsolete violation, and
 * under the read-only ratchet the prune attempt fails the build (verified).
 */
@AnalyzeClasses(
    packages = "tech.krpc",
    importOptions = {ImportOption.DoNotIncludeTests.class, ArchitectureTest.OnlyCoreModules.class})
public final class FlagResolutionGateTest {

    /**
     * NOTE: this field name and the rule description below are referenced by ADR-0003 — renaming
     * either is a documentation break, and changing the description also orphans the frozen store
     * entry (which then fails loudly under the read-only ratchet, see ADR-0005).
     */
    @ArchTest
    static final ArchRule flagsMustNotResolveInStaticInitializers = FreezingArchRule.freeze(
        classes().should(new NoFlagResolutionInStaticInitializer())
            .as("R5: flags must not resolve in static initializers"
                + " (direct env/property reads and known flag accessors) (ADR-0003)"));

    /** {@code java.lang.System} accessors that read the process environment / JVM properties. */
    private static final String SYSTEM = "java.lang.System";
    private static final Set<String> SYSTEM_ENV_READERS =
        Set.of("getProperty", "getenv", "getProperties");

    /** Scalar types a resolved flag is realistically captured into (see false-negative note). */
    private static final Set<String> SCALAR_TYPES = Set.of(
        "boolean", "byte", "short", "int", "long", "char", "float", "double",
        "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
        "java.lang.Long", "java.lang.Character", "java.lang.Float", "java.lang.Double",
        "java.lang.String");

    static final class NoFlagResolutionInStaticInitializer extends ArchCondition<JavaClass> {

        /** Full names of owned code units that hand back an already-resolved flag. */
        private Set<String> flagAccessors = Set.of();
        /** Full names of owned static fields holding an already-resolved flag. */
        private Set<String> flagFields = Set.of();

        NoFlagResolutionInStaticInitializer() {
            super("not resolve flags in static initializers");
        }

        @Override
        public void init(Collection<JavaClass> allClasses) {
            // --- index the owned call/field graph (lambda bodies excluded, see class doc) -------
            Map<String, JavaField> staticScalarFields = new HashMap<>();
            Map<String, Set<String>> callersOf = new HashMap<>();
            Map<String, Set<String>> readersOf = new HashMap<>();
            Map<String, Set<String>> fieldsAssignedByClinit = new HashMap<>();
            Set<String> seeds = new HashSet<>();

            for (JavaClass clazz : allClasses) {
                for (JavaField field : clazz.getFields()) {
                    if (field.getModifiers().contains(JavaModifier.STATIC)
                        && SCALAR_TYPES.contains(field.getRawType().getName())) {
                        staticScalarFields.put(field.getFullName(), field);
                    }
                }
            }
            for (JavaClass clazz : allClasses) {
                for (JavaCodeUnit unit : clazz.getCodeUnits()) {
                    String origin = unit.getFullName();
                    for (JavaCall<?> call : unit.getCallsFromSelf()) {
                        if (isLambdaBody(call)) {
                            continue;
                        }
                        if (isSystemEnvRead(call.getTarget())) {
                            seeds.add(origin);
                        }
                        callersOf.computeIfAbsent(call.getTarget().getFullName(),
                            k -> new HashSet<>()).add(origin);
                    }
                    for (JavaFieldAccess access : unit.getFieldAccesses()) {
                        if (isLambdaBody(access)) {
                            continue;
                        }
                        String field = access.getTarget().getFullName();
                        if (access.getAccessType() == JavaFieldAccess.AccessType.GET) {
                            readersOf.computeIfAbsent(field, k -> new HashSet<>()).add(origin);
                        } else if (unit instanceof JavaStaticInitializer
                            && staticScalarFields.containsKey(field)
                            && access.getTargetOwner().equals(clazz)) {
                            fieldsAssignedByClinit.computeIfAbsent(origin,
                                k -> new HashSet<>()).add(field);
                        }
                    }
                }
            }

            // Anti-vacuous-green: if the seed set is empty the derivation below can only produce
            // an empty accessor set, i.e. a silently green layer 2. Core always reads the
            // environment somewhere (EnvUtils), so an empty seed set means the detector itself is
            // broken (e.g. an ArchUnit upgrade changed the domain model) — fail loudly, and in a
            // way no baseline can absorb.
            if (seeds.isEmpty()) {
                throw new IllegalStateException(
                    "FlagResolutionGateTest found no call to System.getenv/getProperty anywhere in"
                    + " the owned production classes. That is not credible — the flag detector is"
                    + " broken and layer 2 of the gate would pass vacuously; refusing to run.");
            }

            // --- least fixed point over (a) seeds, (b) caller closure, (c) flag-field readers ---
            Set<String> accessors = new HashSet<>(seeds);
            Set<String> fields = new HashSet<>();
            Deque<String> pending = new ArrayDeque<>(seeds);
            while (!pending.isEmpty()) {
                String accessor = pending.remove();
                for (String caller : callersOf.getOrDefault(accessor, Set.of())) {
                    if (accessors.add(caller)) {
                        pending.add(caller);
                    }
                }
                // A <clinit> that resolves a flag freezes every scalar static field it assigns;
                // whoever reads such a field hands the frozen value on.
                for (String field : fieldsAssignedByClinit.getOrDefault(accessor, Set.of())) {
                    if (fields.add(field)) {
                        for (String reader : readersOf.getOrDefault(field, Set.of())) {
                            if (accessors.add(reader)) {
                                pending.add(reader);
                            }
                        }
                    }
                }
            }
            this.flagAccessors = accessors;
            this.flagFields = fields;
        }

        @Override
        public void check(JavaClass clazz, ConditionEvents events) {
            Optional<JavaStaticInitializer> maybeClinit = clazz.getStaticInitializer();
            if (maybeClinit.isEmpty()) {
                return;
            }
            JavaStaticInitializer clinit = maybeClinit.get();
            for (JavaCall<?> call : clinit.getCallsFromSelf()) {
                if (isLambdaBody(call)) {
                    continue;
                }
                AccessTarget target = call.getTarget();
                if (isSystemEnvRead(target)) {
                    report(events, clazz, call,
                        "reads the environment directly via <" + target.getFullName() + ">");
                } else if (flagAccessors.contains(target.getFullName())) {
                    report(events, clazz, call,
                        "calls flag accessor <" + target.getFullName() + ">");
                }
            }
            for (JavaFieldAccess access : clinit.getFieldAccesses()) {
                if (isLambdaBody(access)
                    || access.getAccessType() != JavaFieldAccess.AccessType.GET
                    || access.getTargetOwner().equals(clazz)) {
                    continue;
                }
                if (flagFields.contains(access.getTarget().getFullName())) {
                    report(events, clazz, access,
                        "reads resolved flag field <" + access.getTarget().getFullName() + ">");
                }
            }
        }

        private static void report(ConditionEvents events, JavaClass clazz,
                                   JavaAccess<?> access, String what) {
            events.add(SimpleConditionEvent.violated(clazz,
                "Class <" + clazz.getFullName() + "> resolves a flag in its static initializer: "
                + what + " in " + access.getSourceCodeLocation()
                + "; flags must be resolved lazily on the first-use path"
                + " (a static initializer is baked into a native image at build time)"));
        }

        private static boolean isSystemEnvRead(AccessTarget target) {
            return SYSTEM.equals(target.getOwner().getFullName())
                && SYSTEM_ENV_READERS.contains(target.getName());
        }

        /**
         * javac compiles a lambda body into a synthetic method but ArchUnit reports its accesses
         * against the ENCLOSING code unit. A lambda is deferred work, i.e. exactly the lazy form
         * this gate asks for — counting it would be a false RED.
         */
        private static boolean isLambdaBody(JavaAccess<?> access) {
            return access.isDeclaredInLambda();
        }
    }
}
