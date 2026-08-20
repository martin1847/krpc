# Guardrails — shock-in-the-loop deterministic gates

**Shock-in-the-loop（电在回路）: soft prompts steer, hard gates hold the line.**
Prose (docs, checklists, agent instructions) can only steer behavior; what
actually holds the line is a deterministic gate wired into the loop — a hook
that exits 1, a CI job that goes red. Write prose only for judgment calls a
gate cannot make.

A mechanism for krpc service repos (and this repo itself). Kernel, in one loop:

```
real review finding / incident
  → entry in docs/PR_SELF_CHECK.md   (authors walk it before every PR)
  → machine-checkable entries sink into .githooks/pre-push
      (deterministic · pure git+grep · millisecond · diff-scoped — never builds/tests)
  → every check ships with a negative probe (prove it goes red, then revert)
  → next finding feeds the list
```

Rules:

- **Every hook check must cite a real incident or review finding. Never invent
  checks from theory.** New repos start with an empty list.
- The hook is a reminder (`--no-verify` bypasses it). CI re-running the same
  script with `--range` is the actual gate — wire both.
- Prose stays minimal (shock-in-the-loop): if a rule can be enforced by a hook,
  enforce it there and keep only a one-line pointer in the checklist. Longer
  prose is reserved for judgment calls a hook cannot make (e.g. "verify deps on
  a clean classpath").

Start from `pre-push.template` (generic driver, next to this file). Live
instances: this repo's `.githooks/pre-push` + `selfcheck` workflow;
[krpc-starter](https://github.com/martin1847/krpc-starter) ships the same wiring
for new projects.

---

## Live gates in this repo — what each one blocks, and where it is enforced

Verified 2026-08-20 against the script/test that implements each entry. Grouped by
cost **and by trigger surface**, because the two differ: diff-scoped shell
(milliseconds — every push locally, every PR in CI), the unfiltered JVM build
(minutes — push to `dev` + every PR), an unfiltered shell workflow
(`skill-spec-sync`), and two path-filtered workflows (`japicmp`, `native-smoke`)
that a doc-only PR skips entirely, plus the release script (only when publishing).
A gate nobody can name is a gate nobody maintains — hence the inventory.

### Diff-scoped shell — `.githooks/pre-push`

Local hook is the fast reminder (`--no-verify` bypasses it);
`.github/workflows/selfcheck.yml` re-runs the **same script** with
`--range origin/<base>..HEAD` on every PR, and that job is the actual gate.

- **SPEC pair must exist** (`spec_pair_present`, shared precondition of ① and ③) —
  blocks: a pushed tip where `SPEC.md` or `skills/krpc/references/SPEC.md` is
  missing. A missing path is a FAIL, never a skip: until 2026-08-18 both SPEC
  checks returned 0 on a missing path, so deleting or moving either copy made every
  SPEC gate pass silently, locally and in CI.
- **① SPEC mirror drift** — blocks: a change to one copy of the pair without the
  other (blob-hash comparison at the pushed tip). The two files are a
  byte-identical mirror pair (AGENTS.md Repo Facts).
- **② docs-site pushed to `main`** — blocks: a push to `refs/heads/main` touching
  `docs-site/**`. The site deploys only from `dev` (`docs.yml`), so such a fix
  would never reach production. Source: 2026-07-21 baseUrl incident.
- **③ SPEC length cap** — blocks: either SPEC copy exceeding `SPEC_MAX_LINES`
  (**1010** — post-diet 969 + 41 headroom). Raising the cap needs owner approval,
  recorded in the comment on that line. Source: 2026-08-18 SPEC diet (1155 lines of
  accreted prose in a wire contract every agent load pays for).

### Build-time JVM — `gradle build` (`.github/workflows/build.yml`: push to `dev` + every PR)

- **ARCH-001 layer rules + completeness** — `arch-test` `ArchitectureTest`
  (ArchUnit). Blocks: an owned production class in an unclassified layer package,
  or a layer-dependency violation. Frozen-baseline ratchet per ADR-0005: the
  baseline only shrinks. Analysis face is the six core modules
  (`OnlyCoreModules`), ownership-checked by `ScopeGuardTest`.
- **ARCH-002 scan-face completeness** — `ScanFaceCompletenessTest`. Blocks: a
  Gradle module that is neither scanned nor consciously classified — the
  "hardcoded scan-face silent miss" (a new module drifting in un-analyzed while
  the gate still reports green).
- **ADR-0003 flag discipline, static-initializer half** —
  `FlagResolutionGateTest#flagsMustNotResolveInStaticInitializers`. Blocks **new**
  hits only: a `System.getProperty`/`getenv`/`getProperties` read in a `<clinit>`
  (layer 1), or a `<clinit>` that touches an already-resolved flag **at all** — any
  call to a derived flag accessor, or any read of another class's resolved flag field
  (layer 2, a hard gate within its measured face, not a convention). The matcher does
  not look at assignment: a discarded return value, or a value used only inside a
  condition, is reported exactly like one captured into a field. Reads of the class's
  own fields are excluded, and so are lambda bodies — they are the lazy shape the ADR
  asks for.
  Native-image bakes such a read in at build time, so the switch is dead in
  production. `FreezingArchRule`: pre-existing hits are grandfathered in
  `arch-test/archunit_store/` and the baseline only shrinks.
- **That baseline is exactly three lines today, and none of them is debt.**
  `RpcConstants.CI_BUILD_ID` (`RpcConstants.java:28`, a direct `System.getProperty`)
  plus two collateral reads of that already-baked constant from `RpcServiceExpose`'s
  static block (`:55`, `:56`). The source comment says the build-time bake is the
  *intent* — build metadata stamped into the native image — so the freeze records the
  disposition as **UNDECIDED**: an open question awaiting an owner decision under
  umbrella ADR-0003, whose likely answer is an explicit exemption mechanism (allowlist
  or annotation), **not** lazy resolution. **Do not "fix" these three on the strength
  of this gate**; take it to the owner via ADR-0003. (Debts 1-2 — `KrpcOtel.ENABLED`
  and `AbstractHttpHandler.OTEL_ENABLED` — were the other three lines, and are repaid;
  the baseline shrank 6 → 3.)
- **Scope caveat, so green is not mistaken for proof.** The face is the six core
  modules, `System`-style reads and derived accessors — duplicate hand-written reads,
  reflection, and container-injected config (`@ConfigProperty` / `@Value`) are
  invisible to it. Layer 2 also keeps its teeth only while the accessor's own body
  holds the `System` read: route it through a `Supplier`/method reference and layer 2
  silently matches nothing (the anti-vacuous-green guard covers layer 1's seed set
  only).
- **The rest of umbrella ADR-0003 is reviewer-enforced, and it is more than a
  slogan:** the default must be the correct behavior (no correctness or strictness
  fix shipped as an opt-in switch); resolution lazy on the first-use path inside
  `try`/`catch` with safe-side failure; one accessor per flag; effective state logged
  exactly once at INFO or above (WARN when it switches off a default-ON behavior);
  documented value grammar and precedence; and the injection exemption is
  **per read site**, not per flag — a flag injected in one place and hand-read in
  another is still bound for the hand-read part.
- **No hand-written proto in production source** — `NoProtoInProductionSourceTest`.
  Blocks: a `.proto` file under any production module's source set (NS-1: the Java
  interface is the contract).
- **NS-4 — JSON is the real default decode codec** — `rpc-client`
  `DefaultCodecJsonTest`. Blocks: a change that makes a codec-unset envelope decode
  as anything but JSON. Exercised on the ACTUAL path: a DEFAULT envelope through
  `InputMarshaller.parse` yields `getEValue()==0`, and the server-dispatch resolver
  `Serial.Instance.get(...)` maps that to the JSON serial; it also pins
  `RpcClientFactory.globalSerialEnum == JSON`.
- **NS-6 — the agent/MCP surface is opt-in** — `rpc-server-quarkus`
  `McpDefaultOffContractTest`. Blocks: turning the agent surface on by default —
  `@UnsafeWeb.agentTool()` must default `false`, the runtime-retained
  `@ConfigProperty(name="rpc.server.mcp.enabled", defaultValue="false")` on
  `McpHandler` / `McpGetHandler` must stay `"false"` (flipping the production
  `defaultValue` turns it RED), and `KRPC_MCP` unset must resolve to `"false"`.
- NS-1/NS-4/NS-6 are plain JUnit tests, not frozen ArchUnit rules — they express
  contracts bytecode analysis cannot (files on disk, the default wire-decode path,
  annotation/env defaults), ADR-0005. Each lives in its owning module and rides the
  same unfiltered `gradle build`.
- **Known-red exclusion:** the workflow runs `-x :test-server-spring:test`. That is
  a documented hole (two bugs in published modules — `RpcClientAutoConfigure` NPE,
  then `RpcServiceExposer` hanging `SpringApplication.run()`; AGENTS.md Repo
  Facts), not a passing test.

### Path-filtered JVM workflow — contract compat (`japicmp`)

Triggers: push to `dev`, and PRs — but **only** when `rpc-api/**`, `rpc-common/**`,
`**/*.gradle`, `gradle.properties`, the Gradle wrapper, or the workflow itself
changes. A doc-only PR skips this gate entirely.

- **JAPICMP-001** — `gradle japicmpCheck` (`gradle/japicmp.gradle`) diffs the working
  tree's `rpc-api` + `rpc-common` against the **configured** baseline
  `japicmp.baseline` (in `gradle.properties`), and derives its mode from that
  baseline versus the in-dev `version`: on a **patch** bump a binary-incompatible
  change FAILS; on a **minor** bump incompatibilities require
  `-Pjapicmp.acceptBreaking=true`; a major bump is off-policy (SPEC §14.1 freezes
  major at 1). CI passes `--rerun-tasks` so a cached UP-TO-DATE never stands in for a
  real comparison.
- **Known hole in today's configuration:** `japicmp.baseline=1.1.1` while
  `version=1.2.0` and Central's latest **is** 1.2.0. The comparison therefore runs
  against a surface one release behind what consumers already have, and the version
  pair reads as a MINOR bump — so an acknowledged break
  (`-Pjapicmp.acceptBreaking=true`) is still admissible against API that shipped in
  1.2.0, and nothing compares against 1.2.0 until the baseline is advanced.
  Advancing it is a build change, not a doc edit.
- (SPEC §14.2 still describes japicmp as "recommended, NOT wired here" — that line is
  stale; the workflow is the reality.)

### Unfiltered shell workflow — skill bundle sync (`skill-spec-sync`)

- Runs `diff -q SPEC.md skills/krpc/references/SPEC.md` on push to `dev` and on every
  PR, with **no** path filter. Blocks: a drifted skill copy. Overlaps pre-push hook ①
  deliberately — the hook is diff-scoped, this one checks unconditionally, so it
  still fires on a PR that touches neither file.

### Path-filtered native workflow — `native-smoke`

- Triggers: push to `dev`, and PRs touching `**/*.java`, `**/*.gradle`,
  `**/*.properties`, `**/native-image/**`, or the workflow itself; doc-only PRs skip
  it. Builds the DB-free quickstart as a GraalVM native image, boots it and curls the
  agent/MCP endpoints. Blocks: reflection/init gaps that surface only in a native
  image, including runtime-only failures a build-only check would miss.

### Release-time script — `gradle/publish-central.sh`

Not CI: these `die` before anything is uploaded, and the final publish step is
irreversible.

- **preflight** — blocks: a dirty tracked tree (an orchestrator once published from
  a worker's mid-edit checkout), a rebase/merge in progress, or `HEAD` not equal to
  `origin/$RELEASE_BRANCH` (default `dev`) by **rev** comparison (a release commit
  once landed on the wrong local branch). A detached HEAD on the right commit
  passes.
- **`ext-rpc-gen` bundle exclusion** — blocks: re-uploading an existing GAV.
  `ext-rpc-gen` is standalone-versioned and releases on its own cycle; bundling it
  makes Central reject the **whole** bundle (bit the 1.0.0 release).

### Named here because reviewers look for it in the wrong repo

- **The no-client guard is an `ext-rpc` gate, not a krpc one.** `ext-rpc`'s
  `no-client-it` module builds a **server-only** consumer with `rpc-client` absent
  as a native image, and its `native-smoke` workflow asserts the build log is
  CNFE-free **and** still contains the `IGNORE NotFound RpcProcessor:
  tech.krpc.client.ClientContext` line — a positive control, so the leg cannot
  silently stop being a no-client build. It defends the zero-`tech.krpc`-dependency
  invariant of `ext-rpc` ≥ 1.1.0. Nothing in this repo enforces it.

### Convention only — no electricity (`docs/PR_SELF_CHECK.md`)

Listed for honesty: these are judgment calls a hook cannot make, so they hold only
as long as a reviewer walks the list. Leaf-artifact deps resolving on a **clean**
classpath (GEN-NETTY-102); the DTO collection contract, whose hard gate fires at
*consumer* startup and not in this repo's CI (META-ARRAY-001); release titles
carrying real keywords; and "`-x test` locally means the PR says tests were not run
locally".
