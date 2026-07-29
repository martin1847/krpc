# META-ARRAY-001 — DTO collection-type contract: SPEC rule + meta-scan fail-fast

Owner: omp. Branch `fix/meta-array-001` (from origin/dev @ faea061). Scope: krpc repo —
`SPEC.md` (+ mirror), `rpc-server` meta scan + tests. One local commit, no push/PR.

## Part 1 — SPEC contract rule

Added a normative subsection to **§4 DTO rules** (where a service author looks for field-typing
rules), placed right after `### Type avoidance`:

- Anchor: `SPEC.md` §4 → `### Collection fields — List<T>, arrays UNSUPPORTED` (SPEC.md:153-161).
- Content: collection fields MUST use `List<T>`; concrete object arrays (`Zebra[]`) UNSUPPORTED
  (meta does not model array component types → generation references an undeclared type; scan
  fails fast naming DTO+field); primitive arrays (`byte[]`, `int[]`) EXEMPT (binary-payload
  convention); `Map<K,V>` NOT RECOMMENDED — a scan WARN, not an error.
- Normative tone matches the chapter (`MUST` / `NOT RECOMMENDED` / `Exempt`), 8 lines + heading.

MIRROR: `skills/krpc/references/SPEC.md` updated by byte-identical `cp`.
`diff SPEC.md skills/krpc/references/SPEC.md` → empty (verified twice).

## Part 2 — meta-scan fail-fast (`RpcMetaServiceImpl`)

Path: `rpc-server/src/main/java/tech/krpc/server/RpcMetaServiceImpl.java`.

1. **New helper `checkFieldContract(Field)`** (RpcMetaServiceImpl.java:133-153), invoked per
   DTO field inside `clsFields` (:124) before type resolution:
   - Object-array field → `throw new IllegalStateException(...)` naming the declaring DTO class
     (`f.getDeclaringClass().getName()`) + field name + the array type, pointing at the `List<T>`
     remedy and SPEC. Fail at scan time — no broken meta emitted.
   - `Map`-assignable field → `log.warn(...)` once per field, pointing at SPEC (prefer explicit DTO).
2. **Primitive-array exemption**: `ft.getComponentType().isPrimitive()` guards the array error,
   both in the helper and in a getOrAdd backstop.
3. **getOrAdd else backstop** (RpcMetaServiceImpl.java:188-200): object arrays reaching the raw-
   `Class` branch via method arg/res or nested generics (e.g. `List<Zebra[]>`) also fail fast;
   primitive arrays exempt. Direct DTO fields fail earlier in `checkFieldContract` (richer message).

### Primitive-array survey (BEFORE wiring the error)

Repo-wide grep for `<type>[] name` array field/param declarations (main + test + demo/examples).

| Location | Array usage | Kind | Scanned DTO field? | Verdict |
| --- | --- | --- | --- | --- |
| `test-api/.../dto/Img.java:30` | `byte[] img` | primitive | **yes** (DTO field) | EXEMPT — must keep working |
| `test-api/.../dto/Img.java:35` | `int[] intArray` | primitive | **yes** (DTO field) | EXEMPT — must keep working |
| `http-server/.../AbstractHttpHandler`, `rpc-common/.../ProtoWriter`,`StreamDecoder`, `jws/JwsVerify` | `byte[] …` | primitive | no (runtime internals) | n/a |
| `RpcServerBuilder:311 Annotation[]`, `util/ParameterizedTypeImpl Type[]`, `serial Serial SerialEnum[]` | object | no (internal runtime/util) | n/a |
| `*/main(String[] args)`, `arch-test String[]`, `LogRedact String[]` | object | no (entry points / helpers) | n/a |
| commented `ClientFilter[]` (MethodCallProxyHandler:47) | object | no (dead code) | n/a |

**Result:** the ONLY array-typed DTO fields scanned today are `Img`'s `byte[]`/`int[]` — both
primitive. **No object-array DTO field is in use anywhere that works today.** → primitive arrays
EXEMPTED (documented in SPEC + code); **no STOP/BLOCKED condition** (no existing object-array DTO
whose blast radius would need a ruling).

### Tests

`rpc-server/src/test/java/tech/krpc/server/MetaArrayContractTest.java` (JUnit 5, package-private
access to the static scan methods — mirrors `RefUtilsFailFastTest` conventions; plain POJO DTOs,
no lombok since test scope has no lombok):

- `objectArrayField_failsFast_namingDtoAndField` — `Zebra[]` field → `IllegalStateException`
  whose message contains the DTO class name, the field `zebras`, and `List<T>`.
- `primitiveArrayField_isExempt` — `byte[]` + `int[]` → no throw.
- `mapField_scanSucceeds_withWarn` — `Map<String,String>` field → scan succeeds; a logback
  `ListAppender` on the `RpcMetaServiceImpl` logger captures a WARN naming the field `attrs`.
- `listField_isClean_noWarn` — `List<Zebra>` field → no throw, zero WARN events.

WARN capture required a real slf4j backend on the main test classpath (previously slf4j-api only
→ NOP). Added `testImplementation "ch.qos.logback:logback-classic:1.5.18"` to
`rpc-server/build.gradle:59` (same version the `noSdkTest` set already uses; test-scope only — no
framework version bump, no new module, no behavior flag).

**Fail-probe (executed once, then reverted):** inverted the object-array field-name assertion to
`contains("NONEXISTENT_XYZ")` → `objectArrayField_failsFast_namingDtoAndField` FAILED with
`expected: <true> but was: <false>`, output showing the real exception message
(`... DTO ...$ObjectArrayDto field 'zebras' uses unsupported array type Zebra[]; ... MUST use
List<T> ...`). Reverted; suite green again. Proves the tests actually execute and assert.

## Validation record

- `gradle :rpc-server:test --tests "tech.krpc.server.MetaArrayContractTest"` → BUILD SUCCESSFUL,
  4 tests pass; Map WARN observed on stdout.
- `gradle :rpc-server:test` (full module) → BUILD SUCCESSFUL in 5s. `-Djdbc.host=db.example.invalid`
  is set on the test JVM but the executed tests (Otel*, ServerContext*, Jws*, MetaArray*, …) did
  NOT stall on it; no DB-backed test blocked locally this round.
- `gradle :rpc-server:build -x test` → BUILD SUCCESSFUL (compiles).
- `diff SPEC.md skills/krpc/references/SPEC.md` → empty.

**Not validated:** ext-rpc-gen generation output (out of scope — no gen changes); any downstream
service scan (external repos). Full repo build not run (module-scoped per goal).

## Blast radius

Intended behavior change (maintainer-approved, no env flag): any service whose DTO declares an
object-array field (or exposes one via method arg/res or nested generics) will now **fail fast at
meta scan** with a clear DTO+field message, instead of silently emitting meta that omits the array
component type (generated client references an undeclared type → consumer compile error). Survey
found no such DTO in this repo, so no in-repo caller breaks. `Map` fields keep working (WARN only).
Primitive arrays (`byte[]`, `int[]`) unaffected.

## Source-of-truth

No ADR needed (implements a maintainer field ruling; SPEC §4 is the authoring contract updated
here). FOR/NOT FOR boundaries untouched — change stays within `rpc-server` runtime meta scan and
the SPEC authoring chapter.

---

## Round 2 — adversarial review resolution (amends a7290f6)


Two orchestrator policy decisions recorded as rulings, then per-finding resolution. All changes
stay within scope (SPEC + mirror + rpc-server). Central policy now lives in three helpers in
`RpcMetaServiceImpl`: `isExemptPrimitiveArray`, `arrayNotSupported`, and the single choke point
`rejectUnsupportedArray(Type, context)` — shared by the field check and both getOrAdd array paths.

### Orchestrator rulings

- **RULING A (multi-dimensional arrays):** the array exemption covers **single-dimension primitive
  arrays only** (`byte[]`, `int[]`, …). Multi-dimensional arrays of any kind (`int[][]`, `Zebra[][]`)
  are UNSUPPORTED — use `List<List<T>>`. Implemented explicitly via `isExemptPrimitiveArray` =
  `componentType.isPrimitive()` (for `int[][]` the component is `int[]`, not primitive → rejected),
  and stated verbatim in SPEC §4.
- **RULING B (SPEC scope):** the maintainer ruling covers exactly — arrays unsupported (fail-fast),
  `Map<K,V>` not recommended (WARN), `List<T>` as the sequence type to use instead of arrays. SPEC
  wording NARROWED to match enforcement; `Set`/`Collection`/`Deque` are **not** legislated and
  enforcement is **not** widened.

### Per-finding resolution

| # | Sev | Resolution |
| --- | --- | --- |
| 1 | BLOCK | GenericArrayType leak closed: a generic array whose component is NOT a `TypeVariable` (e.g. `List<String>[]`) now hits `rejectUnsupportedArray` → throws. `T[]` (type-variable component) still models as `List<T>`. `RpcMetaServiceImpl.java` GenericArrayType branch + `rejectUnsupportedArray`. |
| 2 | BLOCK | RULING A. SPEC §4 now says "single-dimension … multi-dimensional arrays (`int[][]`) … UNSUPPORTED — use `List<T>`/`List<List<T>>`"; code implements it explicitly (`isExemptPrimitiveArray`), not accidentally. Test `multiDimensionalPrimitiveArray_failsFast`. |
| 3 | BLOCK | RULING B. SPEC §4 reworded to "use `List<T>`, not arrays" (no unconditional "MUST use List<T>" for all collections). Open question noted below. |
| 4 | minor | Backstop covered through real scan entries: `nestedObjectArrayInGeneric` (`List<Zebra[]>` via field scan → raw-Class backstop), `genericArrayWithConcreteComponent` (`List<List<String>[]>` → GenericArrayType branch), `methodArgumentArray` / `methodReturnArray` (via `RpcServerBuilder.buildApiMeta` + `RpcMetaMethod`, the runtime's api scan entry), plus `stringArrayField`. |
| 5 | minor | `checkFieldContract` carries rich `DTO <class> field '<name>'` context (uses `f.getGenericType()`, so erased `T[]`→`Object[]` no longer mis-rejects a type-variable array). The getOrAdd backstop has no cheap declaring context → reports array type + `List<T>` remedy only. No new plumbing added. |
| 6 | minor | Map WARN deduped by `declaringClass#field` via a static `ConcurrentHashMap.newKeySet()` (`WARNED_MAP_FIELDS`) so the 3 server metas (full/web/mcp) warn once. Test `mapWarn_isDedupedAcrossScans` (two scans → exactly one WARN). |
| 7 | minor | Test hygiene: `ListAppender` attached before the body; `assertDoesNotThrow` + WARN assertions run inside `try`, appender detached in `finally` (no leak on assertion failure). |

### Open question (not verified, not ruled)

`Set<T>` / `Collection<T>` / `Deque<T>` DTO fields are NOT validated — they flow through the
`ParameterizedType` branch like `List<T>`. Whether `ext-rpc-gen` emits readable client code for a
non-`List` `Collection` was NOT verified this round and is NOT covered by the maintainer ruling.
Left as an open question; enforcement deliberately not widened (RULING B).

### Round 2 validation

- Fail-probe (executed once, reverted): neutered only the GenericArrayType clause of
  `rejectUnsupportedArray` → ONLY `genericArrayWithConcreteComponent_failsFast_viaGenericArrayBranch`
  flipped red (ClassCastException in the branch instead of the clean `IllegalStateException`),
  proving that test exercises the #1 path and the guard is load-bearing. Reverted → green.
- `gradle :rpc-server:test --tests MetaArrayContractTest` → 11 tests, 0 failures.
- `gradle :rpc-server:test` (full module) → BUILD SUCCESSFUL; no infra stall.
- `gradle :rpc-server:build -x test` → BUILD SUCCESSFUL. `diff SPEC.md skills/krpc/references/SPEC.md` empty.
