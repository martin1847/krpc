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

Verified 2026-08-20 against the script/test that implements each entry. Three
families, by cost: diff-scoped shell (milliseconds, every push/PR), build-time JVM
(minutes, every PR), release-time script (only when publishing). A gate nobody can
name is a gate nobody maintains — hence the inventory.

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
  `FlagResolutionGateTest#flagsMustNotResolveInStaticInitializers`. Blocks: a
  `System.getProperty`/`getenv` read or known flag accessor called from a
  `<clinit>` (native-image bakes such a read in at build time, so the switch is
  dead in production). Frozen baseline, shrink-only. **Scope caveat, stated so
  green is not mistaken for proof:** it sees only the six core modules and only
  `System`-style reads — duplicate hand-written reads, reflection, and
  container-injected config (`@ConfigProperty` / `@Value`) are invisible to it.
  The rest of umbrella ADR-0003 (lazy first-use resolution, one INFO+ log line of
  effective state, never copying an accessor result into a static field) is
  reviewer-enforced convention.
- **No hand-written proto in production source** — `NoProtoInProductionSourceTest`.
  Blocks: a `.proto` file under any production module's source set (NS-1: the Java
  interface is the contract).
- **Known-red exclusion:** the workflow runs `-x :test-server-spring:test`. That is
  a documented hole (two bugs in published modules — `RpcClientAutoConfigure` NPE,
  then `RpcServiceExposer` hanging `SpringApplication.run()`; AGENTS.md Repo
  Facts), not a passing test.

### Build-time JVM — contract compat (`japicmp` workflow, PRs touching `rpc-api`/`rpc-common`/build wiring)

- **JAPICMP-001** — `gradle japicmpCheck` (`gradle/japicmp.gradle`) diffs the
  working tree's `rpc-api` + `rpc-common` against the last released Central
  baseline (`japicmp.baseline`) and encodes the SPEC §14.1 version policy: on a
  **patch** bump a binary-incompatible change FAILS; on a **minor** bump
  incompatibilities require `-Pjapicmp.acceptBreaking=true`; a major bump is
  off-policy (major is frozen at 1). Run with `--rerun-tasks` so a cached
  UP-TO-DATE never stands in for a real comparison. (SPEC §14.2 still describes
  japicmp as "recommended, NOT wired here" — that line is stale; the workflow is
  the reality.)
- **Skill bundle sync** — `skill-spec-sync` workflow `diff -q SPEC.md
  skills/krpc/references/SPEC.md` on push to `dev` and every PR. Overlaps hook ①
  deliberately: the hook is diff-scoped, this one checks unconditionally.
- **native-smoke** — builds the DB-free quickstart as a native image and boots it,
  curling the agent/MCP endpoints. Blocks: reflection/init gaps that only appear in
  a native image, including runtime-only failures a build-only check would miss.

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
