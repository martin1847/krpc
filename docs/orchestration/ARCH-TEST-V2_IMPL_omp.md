# ARCH-TEST-V2 — scope-guard hardening + NS gate completion (impl findings, omp)

Owner: omp. Worktree `/Users/martin/Garden/middleware/wt-arch002`, branch
`feat/arch-test-v2` (cut from origin/dev @ 5e5b120). Reviewer: codex. Commits LOCAL.
Date: 2026-07-17. **r1 review (`ARCH-TEST-V2_REVIEW_codex_r1.md`, REQUEST-CHANGES)
incorporated — all 4 blocking + the advisory closed. r2 closure left one blocking (inventory
task staleness); fixed below.**

## Summary

Hardened the `arch-test` gate against the "hardcoded scan-face silent miss" class
(ARCH-002 absorb) and made NS-1 / NS-4 / NS-6 executable. **No production code changed.
Zero new `FreezingArchRule`s — the committed `archunit_store/` and `archunit.properties`
are byte-for-byte unchanged.** All four new guards are plain JUnit tests because the facts
they check (the build's project graph, source files on disk, the default wire-decode path,
annotation/env defaults) are not bytecode dependencies ArchUnit can see.

## What changed (8 files)

| File | Module | Purpose |
|---|---|---|
| `arch-test/build.gradle` | arch-test | `writeArchInventory` task emits Gradle's evaluated project model |
| `arch-test/.../BuildInventory.java` | arch-test | reads that model (shared by both gates) |
| `arch-test/.../ScanFaceCompletenessTest.java` | arch-test | ARCH-002 scan-face completeness |
| `arch-test/.../NoProtoInProductionSourceTest.java` | arch-test | NS-1 no-proto-in-src |
| `rpc-client/.../DefaultCodecJsonTest.java` | rpc-client | NS-4 JSON default codec (wire path) |
| `rpc-server-quarkus/build.gradle` | rpc-server-quarkus | `quarkus-arc` testImplementation (for `@ConfigProperty`) |
| `rpc-server-quarkus/.../McpDefaultOffContractTest.java` | rpc-server-quarkus | NS-6 flags default OFF |
| `docs/decisions/ADR-0005-architecture-gates.md` | docs | ARCH-002 addendum |

## r2 blocking finding — inventory task staleness (closed)

**Finding:** `writeArchInventory` declared outputs but NO inputs, so Gradle marked it
UP-TO-DATE and a stale `projects.tsv` survived an ordinary `:arch-test:test` (no
`--rerun-tasks`) after a settings/source/dependency edit — recreating the scan-face false
green on a normal run.

**Fix (`arch-test/build.gradle`):**
1. `writeArchInventory` is marked `outputs.upToDateWhen { false }` — it ALWAYS regenerates
   the inventory (the evaluated model is recomputed every configuration; the task writes two
   small files in a few ms). Simplest fully-staleness-proof choice; cheaper than enumerating
   every model input dimension and risking a miss. **Tradeoff:** the task runs on every
   build (negligible cost).
2. `test` declares `inputs.files(writeArchInventory)`, so the test task re-runs whenever the
   inventory CONTENT changes — i.e. whenever the project model changes — even on a plain
   `:arch-test:test`. When nothing changed, content hashes match and test stays up-to-date
   (no wasteful re-runs).

Chain: model change → `writeArchInventory` always regenerates → `projects.tsv` content
changes → `test` inputs change → `test` re-executes → RED. No stale snapshot possible.

**Self-proof (reviewer's exact scenario, NO `--rerun-tasks`):**
1. plain `:arch-test:test` → GREEN (ScanFace failures=0).
2. add `include("rpc-phantom")` + dummy dir.
3. plain `:arch-test:test` → **RED**, BUILD FAILED, ScanFace failures=2 — "not classified
   by arch-test: [rpc-phantom]" (both the Gradle-model check and the filesystem check fire).
4. revert → plain `:arch-test:test` → GREEN (failures=0).

Frozen store / `archunit.properties` / `settings.gradle` unchanged across the sequence.

## r1 blocking findings — how each was closed

### #1 + #2 — settings.gradle parser bypass / module-discovery + custom projectDir

Replaced the hand-rolled single-quoted, line-prefix parser (both tests) with Gradle's
**authoritative evaluated project model**. `arch-test/build.gradle` adds a
`writeArchInventory` task that emits, into `build/arch-inventory/`:

- `projects.tsv` — one row per included subproject: `<gradlePath>\t<projectDir>\t<mainSrcDirs>`,
  captured from `rootProject.allprojects` after `gradle.projectsEvaluated`.
- `scanned-deps.txt` — arch-test's `testImplementation project(...)` paths (from the
  dependency model).

`BuildInventory` reads them. Because the set comes from Gradle itself, **every include form
is covered** (`include 'x'`, `include "x"`, `include('x')`, multiline), plus intermediate
projects (Gradle materializes `:examples`) and custom `projectDir`. Consequences:

- **ScanFaceCompletenessTest** now classifies every project from the model, pins
  `MODULES == testImplementation` deps, and adds a third filesystem-independent assertion:
  every first-level `build.gradle`-bearing directory must be classified (a nested standalone
  build with its own `settings.gradle`, e.g. `benchmark`, is skipped as not-a-subproject —
  this was surfaced by the new check and handled honestly).
- **NoProtoInProductionSourceTest** derives each production module's real `projectDir` from
  the model and scans the UNION of the whole `<projectDir>/src` tree (catches an unregistered
  `src/main/proto/x.proto`) and every registered main source-set root (catches a root placed
  outside `src`). Custom `projectDir` is resolved via Gradle; a fully custom source root
  *outside* `src` that Gradle doesn't report as a main source-set dir is the only residual
  gap and is noted in the test. A production module with a non-existent `projectDir` is a hard
  error, never treated as empty.
- `EXCLUDED` gained `examples` (the aggregator project the model surfaces).

### #3 — NS-6 never observed the production (`@ConfigProperty`) default

The prior test read Java field defaults (always `false` without injection), so flipping the
annotation `defaultValue` to `"true"` would have stayed green. Now `McpDefaultOffContractTest`
reflects the runtime-retained `@ConfigProperty` on `McpHandler.mcpEnabled` /
`McpGetHandler.mcpEnabled` and asserts `name=="rpc.server.mcp.enabled"` and
`defaultValue=="false"` — this bites an annotation flip. The `enabled()` true-flip stays as a
separate non-vacuity check; the OFF-branch and `KRPC_MCP`-unset checks remain (env-guarded).
The `@UnsafeWeb.agentTool()` annotation-default assertion is kept.

**Tradeoff (stated):** reading `@ConfigProperty` needs MicroProfile Config on the test
classpath (`quarkus-arc` is `compileOnly` for main; `compileOnly` is not inherited by test),
so it is added as `testImplementation`. This is reflection only — no CDI container is booted
(plain JUnit, not `@QuarkusTest`). A full `@QuarkusTest` proving end-to-end injection was not
added; the annotation-default reflection is the honest minimal bite.

### #4 — NS-4 did not exercise the real default wire path

Now `DefaultCodecJsonTest` parses a DEFAULT (codec-unset) envelope through the production
`InputMarshaller.parse` → `InputProto.getEValue()==0`, then feeds that through the EXACT
server-dispatch resolver `Serial.Instance.get(arg.getEValue())` (`UnaryMethod.java:183`,
`invoke/DynamicInvoke.java:15`) and asserts the result is the JSON serial. So an
envelope/generator change that makes an unset input select a non-JSON codec fails here — not
just a change to `SerialEnum.JSON_VALUE`. Javadoc now cites the generated `getEValue()` and
the dispatch resolver as the source of truth (the checked-in `proto/internal.proto` has its
`e` field commented out and is NOT canonical for the runtime path). The client registration
default (`RpcClientFactory.globalSerialEnum == JSON`) assertion is retained.

### #5 (advisory) — Spring adapters outside R1–R4

Added a policy-review trigger to the ADR-0005 addendum: the Spring adapters currently hold
only autoconfig glue; before non-trivial runtime behavior is added to any adapter, revisit
adapter runtime ownership (move into the scan face, or a separately scoped adapter gate). The
scan-face `EXCLUDED` comment references it.

## Freeze discipline

Zero new `FreezingArchRule`s → `arch-test/archunit_store/` and `archunit.properties` are
untouched (`git status -s` on both = empty across every probe round). The ADR-0005 store
procedure was followed vacuously (nothing to write). Nothing was loosened.

## Verification

- `gradle :arch-test:test :rpc-client:test :rpc-server-quarkus:test --rerun-tasks
  --max-workers=2` — BUILD SUCCESSFUL. Per-class, all `failures=0 errors=0 skipped=0`:
  ArchitectureTest 8, ScopeGuardTest 6, ScanFaceCompletenessTest 3,
  NoProtoInProductionSourceTest 1, DefaultCodecJsonTest 3, McpDefaultOffContractTest 6.
  (NS-6 `skipped=0` ⇒ the env-guarded branches actually ran; `KRPC_MCP` unset here.)

### Self-proofs (red/green)

- **Scan-face, double-quoted:** `include "rpc-phantom"` (+ real dummy dir) → RED, **2
  failures** (`everyIncludedModuleIsConsciouslyClassified` via the Gradle model AND
  `everyOnDiskModuleDirectoryIsClassified` via the filesystem) — "not classified by
  arch-test: [rpc-phantom]". Removed → green.
- **Scan-face, parenthesized:** `include("rpc-phantom")` (+ real dummy dir) → RED, same 2
  failures / message. Removed → green. (Both forms prove the Gradle-model parse; the old
  text parser would have silently ignored both.)
- **NS-1:** seeded `rpc-api/src/main/proto/seed.proto` (an unregistered `proto/` dir the
  registered-source-set walk alone would miss) → RED — "Offenders:
  [rpc-api/src/main/proto/seed.proto]". Removed → green.
- Frozen store / `settings.gradle` / `archunit.properties` restored/unchanged after every
  probe.

## Boundaries touched

- FOR/NOT-FOR: none crossed. arch-test remains test-only/unpublished. The `quarkus-arc`
  addition to rpc-server-quarkus is `testImplementation` (no published-artifact impact); no
  production source changed.
- Source-of-truth: ADR-0005 gets an ARCH-002 addendum (no existing decision reversed; still
  `accepted`). No roadmap/module-evolution status change needed.

## Not validated / residual

- Did NOT run the full root `gradle check` (ADR-0005: integration-heavy modules connect to
  an internal MySQL host and can hang locally). Scoped module test tasks were run instead
  (the ADR's recommended proof path).
- NS-6 asserts the `@ConfigProperty` injection default via reflection, not an end-to-end
  `@QuarkusTest` container boot (tradeoff stated in #3).
- NS-1 residual gap: a production source root placed fully OUTSIDE `src` that Gradle does not
  report as a main source-set dir would not be walked (none exist in this repo; noted in the
  test).
