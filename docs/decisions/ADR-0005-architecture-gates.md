# ADR-0005: Architecture Gates (ArchUnit)

Status: accepted

Date: 2026-07-16

## Context

The umbrella `docs/NORTH_STAR.md` states non-negotiable principles (NS-1..NS-8)
and, in its agent-era doctrine, that "a structural invariant is only real once a
machine checks it": gates must fail builds, each gate must bite the whole
violation *class* (not one planted instance), and violation baselines only shrink.

Before this ADR krpc had zero executable architecture constraints — every
boundary lived in docs only (README, SPEC, ADR-0001 FOR/NOT-FOR, NORTH_STAR).
Nothing stopped a future change from letting `rpc-api` depend on runtime
internals, letting core grow a service-discovery/telemetry backend (NS-3), or
pulling a JDK-internal API that breaks native-image (NS-7). An ADR nobody checks
is a comment.

## Decision

A new test-only, **unpublished** Gradle module `arch-test` runs
[ArchUnit](https://www.archunit.org/) (1.4.1, JUnit 5). Its analysis subject is
**only the classes we own** — the six core modules `rpc-api`, `rpc-common`,
`rpc-client`, `rpc-server`, `rpc-server-quarkus`, `http-server` — pinned by an
`OnlyCoreModules` `ImportOption` whose invariant is **absolute-prefix ownership**,
anchored to THIS repo's canonical root (not a path-shape heuristic). At test
runtime the repo root is the parent of the arch-test project dir (Gradle sets the
test JVM working dir to that project dir; verified), canonicalized via
`toRealPath()`. Ownership is decided entirely in `java.nio.Path` (never raw
string/URI prefixing): a location's on-disk file — the jar for `jar:` URIs, the
class file for `file:` URIs, resolved via `Paths.get(URI)` so percent-encoding is
handled — is owned iff, canonicalized, it `Path.startsWith` one of
`<repoRoot>/<module>/build` for the six modules. This kills the whole
scope-substitution class at once: a Gradle-cache jar, an `.m2` jar, ANOTHER
checkout (e.g. `/opt/vendor/krpc/rpc-client/build/...`), or a relocated buildDir
all fall outside the owned build dirs and are rejected — the build fails RED, not
silently green. Path comparison (not string) also means a symlinked project dir
(logical vs physical) or a checkout path with spaces (`%20` vs " ") cannot cause a
false rejection or a crash. Non-file/jar or unparseable URIs are rejected, not
crashed. This also excludes transitive `tech.krpc.*` dependency jars that merely
share the prefix — notably the published `tech.krpc.ext:ext-rpc` pulled in by
`rpc-server-quarkus` — which a bare `packages = "tech.krpc"` import would otherwise
scan. A plain (non-frozen) `ScopeGuardTest` asserts the filter accepts this repo's
local build output (incl. a symlinked view and a spaced path) and rejects
cache/`.m2`/other-checkout URIs, and that every core module contributes owned
classes with no unowned class analyzed. Test classes are excluded too (production
classes only).

It is not published (it applies no `gradle/upload.gradle`, so it produces no
Maven publication and never matches the central-bundle glob — same non-publish
precedent as `examples/quickstart`, commit `0c0ca37`). Being an ordinary
subproject, its `test` task is part of the root `check`/`test` lifecycle
automatically.

### Rule set v1 (each cites its NS-ID in code)

- **R1 (structure).** `tech.krpc` slices are free of package cycles. Slicing is
  global on the merged classpath, so cross-module cycles are caught too — strictly
  stronger than per-module slicing.
- **R2 (layering, NS-1/NS-2).** Package-level dependency direction:
  `rpc-api` (`tech.krpc.annotation`, `tech.krpc.model`) must not depend on
  common/client/server/http; `rpc-common`
  (`tech.krpc.{common,context,filter,internal,serial,util}`) must not depend on
  client/server/http; `rpc-client` (`tech.krpc.client`) and `rpc-server`
  (`tech.krpc.server`) must not depend on each other. Package roots were derived
  from the code, not guessed. Some directions are already enforced by the Gradle
  dependency graph; they are encoded anyway as cheap insurance against future dep
  edits. **Plus an anti-vacuous-green completeness guard** (also frozen): every
  owned production class under `tech.krpc..` must reside in one of the classified
  layer roots. A new, unclassified package (e.g. `tech.krpc.newpkg`) turns the
  build RED and forces a conscious layer assignment — so the direction rules,
  which key off those roots, can never be silently bypassed by an unclassified
  package that no `that()` predicate happens to match.
- **R3 (NS-3 denylist).** No owned production class (all of `tech.krpc..`, no
  allowlist) may depend on service-discovery / registry / load-balancing /
  telemetry-backend packages: `io.kubernetes..`, `io.fabric8..`,
  `com.ecwid.consul..`, `com.orbitz.consul..`, `com.netflix..`,
  `org.apache.zookeeper..`, `org.apache.curator..`, `io.etcd..`,
  `com.alibaba.nacos..`, `org.springframework.cloud..`, `io.micrometer..`,
  `io.opentelemetry.sdk..`. (OpenTelemetry *API* is fine; only the *SDK* export
  backend is denied.)
- **R4 (NS-7).** No owned production class (all of `tech.krpc..`) may depend on
  `sun..`, `com.sun..`, or `jdk.internal..` — open-world internal APIs that
  jeopardise closed-world native-image builds.

### Rules deliberately NOT added

The `RpcResult` method-contract shape (return type must be a `RpcResult`
subtype) is already enforced at runtime by `RefUtils` during interface scanning.
Re-encoding it in ArchUnit would duplicate runtime enforcement without adding
compile-time value (ArchUnit sees bytecode, and the runtime check already fires
on every registered interface). It is intentionally left out; add it only if a
compile-time gap appears.

### Freeze-ratchet discipline (baselines only shrink)

Every rule is wrapped in `FreezingArchRule`. The violation store is a committed
text store under `arch-test/archunit_store/` (one file per rule, plus a
`stored.rules` index). `arch-test/src/test/resources/archunit.properties` locks
the baseline as a **fully read-only one-way ratchet** — day-to-day/CI runs can
never mutate the store:

```
freeze.store.default.path=archunit_store
freeze.store.default.allowStoreCreation=false
freeze.store.default.allowStoreUpdate=false
freeze.refreeze=false
```

- `allowStoreCreation=false` — the store directory can never be auto-created.
- `allowStoreUpdate=false` — the store can never be written. This closes the back
  door where `allowStoreUpdate` defaults to **true**: without it, an unknown rule
  description (a new rule, or a renamed `.as(...)`) silently creates its mapping
  and freezes ALL its current violations as passing baseline. With it false, a
  new/renamed rule with no committed entry FAILS loudly
  (`StoreUpdateFailedException`), and no existing entry can be rewritten.
- `freeze.refreeze=false` — a NEW violation of a frozen rule can never be
  absorbed; it fails the build. Existing (grandfathered) violations still pass.

Because the store is read-only under the committed config, **every** baseline
change — adding a rule OR shrinking after a fix — is a deliberate, reviewed,
committed act, never a silent CI side effect. (This trades ArchUnit's automatic
prune-on-fix for review visibility: the store diff in the commit is the audit
trail, and CI can never grow the baseline.)

Initial per-rule frozen counts at introduction: R1 = 2 cycles (slices
`common` / `internal` / `util`); R2 direction rules = 0; R2 completeness guard =
0; R3 = 0; R4 = 0.

### Adding / regenerating a rule (empirically-proven procedure)

The `-Darchunit.*` system-property override does **NOT** work — ArchUnit reads
freeze config only from `archunit.properties` on the classpath. The only working
path to write the store:

1. Write/adjust the `@ArchTest` `FreezingArchRule`, citing its NS-ID.
2. Temporarily set BOTH `allowStoreCreation=true` AND `allowStoreUpdate=true` in
   `archunit.properties`, run `gradle :arch-test:test` once (writes the store to
   the current violation set), then set both back to `false`.
3. **Self-prove the checker** (mandatory): seed a deliberate violation of the new
   rule (a throwaway class that trips it), run `gradle :arch-test:test`, confirm
   it FAILS naming the rule, then remove the seed and confirm it passes. The seed
   must never be committed.
4. Review the store diff (it must only shrink for existing rules; a growth is a
   red flag), then commit the rule and its (possibly empty) store together.

## Consequences

- NS-1/NS-2 (R2 + completeness guard), NS-3 (R3), NS-7 (R4) and the anti-rot
  structural invariant (R1) are now build-failing constraints, not advisory docs.
- The root `check`/`test` lifecycle runs the gate. NOTE: bare root `gradle test`
  also reaches integration tests that connect to an internal MySQL host and can
  hang locally; scope proof runs per module (`gradle :arch-test:test`) or exclude
  the integration-heavy modules.
- Grandfathered violations (currently only the 2 R1 cycles) are visible in the
  committed store and can only be removed, never added to.
- **Rider residual risk (`:test-api:test`, accepted).** The rider fixes the
  Gradle 9 no-tests-discovered failure with `failOnNoDiscoveredTests=false`
  because that module's `src/test` are `main()`-based demos, not JUnit tests. The
  residual risk: if real `@Test` classes are later added to `test-api` with a
  misconfigured test engine (so none are discovered), the task would pass silently
  instead of failing. Whoever adds real tests to `test-api` must also wire
  `useJUnitPlatform()` (or re-enable the guard).
- Wiring `arch-test` into GitHub Actions CI is a deliberate follow-up, not part of
  this ADR.

## Limitations

- **Scope-ownership convergence line (orchestrator decision, binding for this
  goal).** After anchoring ownership to this repo's canonical root by `java.nio.Path`
  comparison (NB1–NB3), every silent-green scope-substitution axis is closed: no
  cache jar, `.m2` jar, other checkout, relocated buildDir, symlinked project dir,
  or spaced path can be silently admitted. Any residual path-representation exotica
  that could still arise can only cause a **false RED** — the gate failing loudly on
  a weird checkout — never a false green. That is acceptable and is treated as
  ADVISORY, not blocking.
- **Ownership-chain threat-model boundary (orchestrator decision, binding).** With
  the reverse-symlink anchor guard (NB4) in place, the ownership chain is fully
  closed in canonical `Path` space: repoRoot (canonical) → each owned anchor proven
  to `startsWith` repoRoot (a build dir symlinked outside the checkout fails loudly,
  never admits external classes) → classes proven under those anchors. On top of
  that, the anchors are required to be **plain directories** — a module dir or its
  `build/` that is itself a symlink is rejected loudly (NB5), so intra-repo symlink
  redirection (e.g. `rpc-api/build -> ../rpc-common/build`, which would canonicalize
  to a path still under the root and slip past the under-root check) is closed by
  construction. This exhausts the declared threat model: any remaining escape requires
  adversarial filesystem manipulation INSIDE the checkout beyond symlinking build dirs
  (e.g. bind mounts, TOCTOU/FS races) — an actor with that access can already edit the
  test itself, so that class is OUT OF the gate's threat model and out of scope for
  this goal.

## Evidence

ARCH-001 (2026-07-16, `docs/orchestration/ARCH-001_IMPL_omp.md`):
`gradle :arch-test:test` green against the frozen baseline; ratchet proven (store
bytes identical across a clean re-run). B1 back-door probe: a new-description rule
under the locked store FAILED loudly with `StoreUpdateFailedException`, removed →
green, store md5 unchanged. B2 self-proof: a seed in a NEW package
(`tech.krpc.zznew`) importing `com.sun..` made BOTH the completeness guard and R4
fail naming their rules; removed → green. Executed under root `gradle check`
(integration-heavy modules excluded).

NB1 (r2): the pre-hardening filter matched a bare `/<module>/` segment, which also
matches Gradle-cache jar paths; the `ScopeGuardTest` reject cases were added and the
filter tightened to `/<module>/build/`, then to absolute-path ownership. NB2 (r3):
ownership anchored to the canonical repo root — reverting to the path-shape form made
the other-checkout probe FAIL, restore → green. NB3 (r4): comparison moved fully into
`java.nio.Path` — reverting to raw-string prefixing made the symlinked-owned-path
probe FAIL (false RED), restore → green; the spaced-path probe runs without crashing.
NB4 (r5): a reverse-symlink anchor guard requires each canonicalized owned anchor to
`startsWith` the canonical repo root, else fails loudly (`IllegalStateException`); a
unit probe proves the guard accepts an in-root anchor and rejects an outside-root one.
NB5 (r6): the anchors must be plain directories — `requireNotSymlinked` rejects a
symlinked module dir or `build/` (intra-repo redirection); a scratch-temp-dir probe
(`modx/build -> ../mody/build`) proves the guard trips and a plain dir passes.
All six `ScopeGuardTest` cases green; frozen store md5 unchanged across every round.
