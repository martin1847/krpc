# GENDET-002 — Topological DTO emission order (dependency-safe + deterministic)

Owner: omp · Module: `ext-rpc-gen` · Branch: `fix/gendet-002` (from origin/dev @ faea061)

## Summary

GENDET-001 made DTO emission deterministic via an alphabetical `(name, originName)` sort.
Deterministic ≠ dependency-safe: in single-file TS output, a DTO that references another and
sorts alphabetically *before* it produces a runtime forward reference — a hard TDZ under
`emitDecoratorMetadata` (`__metadata("design:type", X)` → `Cannot access 'X' before
initialization`). Alphabetical order only worked by luck.

The DTO sort in `Gen.genApiMetaRoot` is now a **topological sort** (referenced-before-referencing)
over the **strongly-connected-component condensation** of the dependency graph, drained through an
alphabetically-ordered ready-set, with the existing `(name, originName)` key (plus a content
fingerprint and stable index) as the tie-break. Output stays fully deterministic. Cycles never
fail the build: only genuine multi-node SCCs (real cycles) are emitted alphabetically within their
condensation slot and a WARN names their members; DTOs merely downstream of a cycle keep their
topological placement. The sort lives in `Gen.java` (not per template), so it applies uniformly to
every emit target (TS / Miniprogram / Dart / yaml).

> **Round 2** (adversarial review of `4f89f78`) hardened this: see the [Round 2](#round-2) section
> for the SCC fix (blocking), array-element edges, tie-break fingerprint, and test hardening. The
> anchors in "What changed" below are Round-1 line numbers; the Round 2 section carries current
> anchors.

## What changed

- **`ext-rpc-gen/src/main/java/tech/krpc/ext/gen/Gen.java`**
  - `Gen.java:169` — the alphabetical `dtos.sort(...)` call is replaced by
    `var orderedDtos = topoOrderDtos(dtos);` (`Gen.java:170` puts it into the template model).
  - `Gen.java:236` `topoOrderDtos(List<Dto>)` — new. Sorts to the GENDET-001 base order first
    (so the stable index tie-break reflects alphabetical rank → total order even on `(name,
    originName)` ties), builds a name-keyed dependency graph, and drains an ordered
    `PriorityQueue` ready-set.
  - `Gen.java:314` `collectDtoRefs` / `Gen.java:326` `collectTypeRefs` — new. Edge extraction:
    a DTO depends on every generated DTO of this run that appears in its field types, including
    generic type arguments (`List<Zebra>`, `Map<K,V>` args) at any nesting depth. Matched by
    **post-remap simple name**, not object identity — see "Why name matching".
  - `Gen.java:303` `LOG.warn(...)` — cycle WARN naming the participants. New slf4j `LOG`
    field at `Gen.java:52`.
- **`ext-rpc-gen/build.gradle`** — module had no test source set. Added `junit-jupiter 5.11.4`,
  `junit-platform-launcher`, `logback-classic 1.5.18` (slf4j backend, for WARN capture), and
  `test { useJUnitPlatform() }` (the root `subprojects{}` block does **not** enable it).
- **`ext-rpc-gen/src/test/java/tech/krpc/ext/gen/TopoEmitOrderTest.java`** — new, 7 tests.

Nothing about *what* is emitted changed — only DTO ordering. No template content, no version
bumps, no other modules.

## Why name matching (not object identity)

The meta model is JSON round-tripped in `Gen.scan` (`JsonUtils`, plain Jackson, no
`@JsonIdentityInfo`). Reference identity is therefore **not** preserved: a field's
`PropertyType.rawType` for a `Zebra` field is a *distinct* `Dto` instance from the top-level
`Zebra` node. Edges are matched by `getName()` after remapping. Remapping recurses into field
`rawType` names (`NameRemapping.remapping`), and generated-DTO names are not in any `nameMapping`,
so they are stable and identical on both sides. Duplicate post-remap names (the GENDET-001
`Integer`/`Long` → `number` counterexample) are tolerated deterministically: those are primitives
filtered out by `Dto::hasChild` before the sort, and any residual duplicate name links an edge to
every node carrying it, with the stable-index tie-break guaranteeing a total order.

## Superclass edges — N/A (verified, not skipped)

The task listed a superclass edge (`Apple extends Zebra`). This dependency form **cannot exist**
in this pipeline:

1. Inheritance is **flattened upstream**: `RpcMetaServiceImpl.cls2dto` (rpc-server) walks the
   `getSuperclass()` chain and copies every superclass declared field into `Dto.fields`
   (`cls2dto` loop `for (Class<?> c = type; c != null; c = c.getSuperclass())`).
2. The meta model (`meta/Dto.java`) carries **no** superclass/parent reference.
3. **No DTO template emits an `extends` clause** — `class ... extends` appears only in
   `Service.dart` for the fixed `BaseService`, never in `DTO.ts` / `DTO.dart` / `DTO.yaml`.

So a subclass emits as a self-contained class with the superclass fields inlined — no superclass
forward reference, no TDZ. This is reported (not a silent scope cut) and locked in by the
`noExtendsClauseEmittedForDtos` regression test. Per the task's block clause, the meta model
carries **enough** type-reference info for the only representable dependency form (field / generic
edges), which is the actual production incident — so this is not a blocker.

Array element types: a `GenericArrayType` is modeled upstream as `List` + a generic argument
(`cls2dto`), so custom element types are captured by the generic-argument recursion; a raw
`Object[]`-style element Dto has no fields (`hasChild()==false`), is never emitted, and so is
correctly not an edge.

## Iteration-order sweep (task item #3)

| Emission collection | Source | Status |
|---|---|---|
| DTO list order | `HashMap.values()` upstream | **Fixed here** — topological + alphabetical tie-break |
| Service import list | `Api.getCustomerDtos()` | Already `TreeSet` (GENDET-001) ✓ |
| Service order (files) | `groupingBy` HashMap | Already sorted (GENDET-001) ✓ |
| Method order | per-service list | Already `Collections.sort` (GENDET-001) ✓ |
| Field order within a DTO | reflection declaration order | Left as-is — per-compiled-class stable, **not** HashMap-derived; reordering would change intended constructor/field semantics, not just order |
| Annotation order | reflection order | Left as-is — same reasoning |
| Template model map (`root`) | `HashMap` | Accessed by key only, never iterated into output — no ordering effect |

Residual (documented, out of module scope): `NameRemapping.remapping` line ~79 renders
`anno.getProperties()` (a `LinkedHashMap` after Jackson parse) into an *unknown-annotation comment*.
Its order is inherited from the upstream serialized map and is deterministic per upstream build;
the root would be an upstream map type, outside `ext-rpc-gen`. Not changed.

## Validation

- **`gradle :ext-rpc-gen:test`** — green. `build/test-results/test`:
  `tests="7" skipped="0" failures="0" errors="0"`.
- **`gradle :ext-rpc-gen:build -x test`** — BUILD SUCCESSFUL (compiles).
- **JUnit 5 execution proven**: a deliberate failing probe (`fail(...)`) was run first — it failed
  with `org.opentest4j.AssertionFailedError` (JUnit 5 / opentest4j), confirming the test task
  actually executes JUnit 5 via `useJUnitPlatform()`; the probe was then removed and the suite
  re-run green.
- Did **not** run the full-repo `gradle build`/`test` (other modules need `db.example.invalid`, the site-local MySQL of that era).
- **Not validated**: no real consumer TS bundler / `emitDecoratorMetadata` compile was run
  (out of module scope); the tests assert declaration ordering in the generated `.ts`, which is
  the exact property that prevents the consumer-side TDZ.
- **Wire compatibility unaffected**: this changes only *source declaration ordering* in generated
  client files. No field names, types, method names, service names, or serialized shapes change;
  gRPC/JSON wire format is untouched.

### Test → claim anchors

| Requirement | Test | Anchor |
|---|---|---|
| Adversarial (referencer alphabetically before referenced) | `referencerAlphabeticallyBeforeReferenced_stillEmitsReferencedFirst` | `TopoEmitOrderTest.java:106` |
| Generic arg edge `List<Zebra>` | `genericArgumentEdge_listOfReferenced` | `TopoEmitOrderTest.java:117` |
| Nested generic `Map<String, List<Zebra>>` | `nestedGenericArgumentEdge_mapOfListOfReferenced` | `TopoEmitOrderTest.java:126` |
| Superclass N/A (no `extends`) | `noExtendsClauseEmittedForDtos` | `TopoEmitOrderTest.java:136` |
| Cycle: succeeds, deterministic, WARN | `cycle_generationSucceedsDeterministicallyAndWarns` | `TopoEmitOrderTest.java:146` |
| Determinism: byte-identical across runs | `determinism_byteIdenticalAcrossRuns` | `TopoEmitOrderTest.java:180` |
| Ordered ready-set (alphabetical tie-break) | `independentNodes_emittedInAlphabeticalTieBreakOrder` | `TopoEmitOrderTest.java:187` |

### Implementation anchors

- Sort call: `Gen.java:169`
- `topoOrderDtos`: `Gen.java:236`
- Edge extraction: `Gen.java:314` (`collectDtoRefs`), `Gen.java:326` (`collectTypeRefs`)
- Cycle WARN: `Gen.java:303`

## Boundaries

`ext-rpc-gen` only. No version bumps, no template content changes beyond ordering, no other
modules. SPEC/roadmap updates are the orchestrator's.

## Round 2

Adversarial review of `4f89f78`: 1 blocking + 5 minor. All resolved; commit amended (same
message), `gradle :ext-rpc-gen:test` green (9 tests). Module scope unchanged.

### Finding 1 — BLOCKING: residual-append dropped downstream topological placement

Round 1 drained an ordered Kahn queue, then appended the **entire** unresolved residual (cycle
members **and** everything downstream of a cycle) alphabetically. A node merely downstream of a
cycle thereby lost its topological placement → avoidable forward references outside the true cycle.

**Fix (design a — SCC condensation).** `topoOrderDtos` (`Gen.java:250`) now condenses the
dependency graph into strongly-connected components with iterative Tarjan
(`stronglyConnectedComponents`, `Gen.java:353` — iterative to avoid recursion-depth limits on deep
DTO graphs), then topo-sorts the **condensation DAG** through an ordered ready-set keyed by each
SCC's alphabetically-minimum member. A downstream DTO is a singleton SCC and keeps its
topological slot; only a genuine multi-node SCC (a real cycle) is emitted alphabetically within
its slot, and the WARN (`Gen.java:339`) names **only** those members.

*Why (a) over (b):* SCC condensation resolves the whole graph in one deterministic pass and names
exactly the true cycle participants; the "force-release the smallest cycle node on each stall"
variant (b) needs a per-stall cycle-membership detection pass and only names one representative per
break. (a) is clearer for the next maintainer and provably correct for the downstream case.

*Determinism:* SCC membership is invariant of traversal order; the condensation ready-set and the
within-SCC emission are both ordered by the total node comparator, so output is stable regardless
of Tarjan's visitation order.

**Repro test:** `cycleWithDownstream_downstreamKeepsTopologicalPlacement`
(`TopoEmitOrderTest.java:219`) — `M↔N` cycle, `Z→M`, `A→Z`.
- **Fail-before** (observed on `4f89f78` before the fix): `AssertionFailedError: downstream A must
  be emitted after its dependency Z` — the residual `{A,M,N,Z}` was appended alphabetically as
  `A,M,N,Z`, emitting `A` before its dependency `Z`.
- **Pass-after:** order is `M,N` (cycle, alphabetical), then `Z`, then `A`; the WARN names only
  `M, N` (asserted it does **not** name `Z`/`A`).

### Finding 2 — array element edges: module-level linking vs. upstream registration gap

**Orchestrator adjudication:** the *production* array gap is UPSTREAM, out of GENDET-002 scope
(different module, different failure class — a missing meta declaration, not an emission-order
bug), and is filed as a separate issue. rpc-server is NOT touched by this change.

Precise coverage of the module-level `[]` name-strip added here (`collectTypeRefs`,
`Gen.java:431`; strip at `Gen.java:438`; the meta-model analogue of `Class.getComponentType()`):

- **(a) Effective when the component DTO is registered via another path.** If `Zebra` is
  referenced directly somewhere (e.g. a plain `Zebra` field on another DTO) AND appears as
  `Zebra[]` on some DTO, then `Zebra` is a node in this run and the `[]`-strip correctly links the
  `Zebra[] → Zebra` edge, so the referencer is emitted after `Zebra`. This is the case this change
  fixes and tests.
- **(b) NOT covered when the component appears ONLY as `Zebra[]`.** Upstream never registers such a
  component: `RpcMetaServiceImpl.getOrAdd`'s final `else` branch calls `cls2dto` on the raw `Class`
  without an `isArray()` / `getComponentType()` unwrap, so a type reachable only through `Zebra[]`
  is absent from the meta entirely (a missing *declaration*, independent of emission order). No
  module-level ordering fix can conjure a node that was never registered. Pre-existing upstream
  gap, filed separately, **not** covered by this change or its tests.
- **(c) The test manually registers both DTOs.** `arrayElementEdge_concreteArrayType`
  (`TopoEmitOrderTest.java:208`) builds both `Apple` (with a `Zebra[]` field) and `Zebra` as
  top-level nodes, so it exercises the module-level **edge linking** of case (a) — NOT the upstream
  **registration** gap of case (b), which this module's tests cannot reproduce.

GenericArrayType (`T[]`) is unaffected: upstream models it as `List` + a generic arg, captured by
the existing generic-argument recursion.

**Test:** `arrayElementEdge_concreteArrayType` (`TopoEmitOrderTest.java:208`).
- **Fail-before** (observed on `4f89f78`, both DTOs registered): `AssertionFailedError: Zebra
  (Zebra[] element) must be declared before Apple` — the `[]`-strip edge was missing.
- **Pass-after:** `Zebra` precedes `Apple`.

### Finding 3 — test hardening

- **Cycle + downstream:** added (finding 1 repro above), with stated fail-before/pass-after.
- **Determinism:** `determinism_byteIdenticalAcrossRuns` (same input twice, vacuous) replaced by
  `determinism_byteIdenticalAcrossShuffledInput` (`TopoEmitOrderTest.java:186`) — the same graph is
  built twice and seed-shuffled to two **different** input orders (`Random(1)` vs `Random(7)`);
  the two emissions must be byte-identical. This now actually exercises "the sort, not the input
  order, determines emission" and passes.

### Finding 4 — stable-index tie-break froze nondeterministic input order

The `(name, originName)` comparator fell back to the stable input index for fully-tied pairs,
freezing whatever (possibly nondeterministic) upstream order existed. Added a cheap deterministic
tie-break: a **content fingerprint** = `JsonUtils.stringify(dto)`, precomputed once per node
(`Gen.java:258`), inserted as `name → originName → fingerprint → index` (`Gen.java:264`). Two nodes
tying on all four are **byte-identical DTOs**, so their relative order cannot change the emitted
text; the index remains only as a total-order guard. (Production dedupes DTO simple names upstream,
so such ties are already pathological — documented, not over-engineered.)

### Finding 5 — `noExtendsClauseEmittedForDtos` scope, described honestly

`noExtendsClauseEmittedForDtos` (`TopoEmitOrderTest.java`) locks **template behavior only**: the
generated DTO file emits no `extends` clause. It does **not** — and cannot, from this module's
tests — verify the upstream *flattening* claim (that `RpcMetaServiceImpl.cls2dto` copies superclass
fields into `Dto.fields`). That claim rests on reading rpc-server source (`cls2dto`'s
`for (Class<?> c = type; c != null; c = c.getSuperclass())` loop) and is stated as source-read
evidence, not test-locked. Together they establish: no superclass reference exists in the meta
model, and no superclass edge is emittable — so a superclass forward reference cannot occur.

### Round 2 validation

- `gradle :ext-rpc-gen:test` — green; `build/test-results/test`:
  `tests="9" skipped="0" failures="0" errors="0"`.
- `gradle :ext-rpc-gen:build -x test` — BUILD SUCCESSFUL.
- Fail-before evidence for findings 1 and 2 was observed by running the two new tests against the
  unmodified `4f89f78` working tree **before** applying the fixes (both failed with the messages
  quoted above), then re-run green after.
- Not validated (unchanged from Round 1): no real consumer TS bundler / `emitDecoratorMetadata`
  compile; wire compatibility unaffected (source declaration ordering only).

### Round 2 implementation anchors

- Sort call site: `Gen.java:173`
- `topoOrderDtos`: `Gen.java:250` · fingerprint tie-break: `Gen.java:258`, `Gen.java:264`
- `stronglyConnectedComponents` (iterative Tarjan): `Gen.java:353`
- Cycle WARN (names only true SCC members): `Gen.java:339`
- Edge extraction: `collectDtoRefs` `Gen.java:419`, `collectTypeRefs` `Gen.java:431`, array-element
  strip `Gen.java:438`, `addRefs` `Gen.java:458`
- Tests: `cycleWithDownstream…` `TopoEmitOrderTest.java:219`, `arrayElementEdge…`
  `TopoEmitOrderTest.java:208`, `determinism…ShuffledInput` `TopoEmitOrderTest.java:186`

## Round 3

Two small fixes; commit amended (same message), `gradle :ext-rpc-gen:test` green (9 tests).
`ext-rpc-gen` only — rpc-server not touched.

1. **Deliverable-honesty gate — array coverage reworded.** Round 2's Finding 2 overstated array
   coverage. Corrected above: the module-level `[]` name-strip links the `Zebra[] → Zebra` edge
   **only** when the component DTO is registered via another reference path (case a); when a type
   appears **only** as `Zebra[]`, upstream (`RpcMetaServiceImpl.getOrAdd` final `else`, no
   `isArray()`/`getComponentType()` unwrap) never registers it — a pre-existing upstream
   missing-declaration bug, out of GENDET-002 scope, filed separately (case b). The array test
   manually registers both DTOs, so it exercises module edge-linking (a), not the upstream
   registration gap (b) (case c).

2. **Cycle WARN — one per multi-node SCC.** Previously all cyclic SCCs were merged into a single
   post-loop WARN, losing SCC grouping. The WARN now fires inside the condensation drain loop, once
   per multi-node SCC, each naming only that SCC's members, in the deterministic drain order
   (`Gen.java:326`–`Gen.java:335`). Independent cycles are now reported distinctly.

**Verified:** `gradle :ext-rpc-gen:test` — `tests="9" skipped="0" failures="0" errors="0"`;
`gradle :ext-rpc-gen:build -x test` — BUILD SUCCESSFUL. The two existing cycle tests
(`cycle_generationSucceedsDeterministicallyAndWarns`, single `{Alpha,Beta}` SCC;
`cycleWithDownstream_downstreamKeepsTopologicalPlacement`, single `{M,N}` SCC) still pass — each
has exactly one multi-node SCC, so behaviour is unchanged for them; the per-SCC change matters only
when a run contains ≥2 independent cycles.

### Round 3 implementation anchors

- Per-SCC cycle WARN: `Gen.java:326`–`Gen.java:335` (inside the drain loop)
- Array-edge coverage rewrite: findings §"Round 2 → Finding 2" (above)
