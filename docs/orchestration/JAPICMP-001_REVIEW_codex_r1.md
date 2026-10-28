# JAPICMP-001 r1 — Adversarial Review (codex)

**Scope.** Read-only review of `944c8bc` against `origin/dev`: `gradle/japicmp.gradle`, root build wiring, `gradle.properties`, and `.github/workflows/japicmp.yml`. No production source is retained as changed. The contract under review is SPEC §14: major remains `1`, a minor may carry a breaking contract change, and a patch is compatible-only; §14.2 requires a **binary/source** compatibility check.

**Verdict: REQUEST-CHANGES.** The implementation really compares against the Central `1.1.1` artifacts, fails loudly when they cannot be fetched, and PATCH mode does not let `acceptBreaking` through. It nevertheless does not encode two mandatory parts of the stated policy: major-version immutability and source compatibility.

## Findings

### F1 — P1: MAJOR is a warning plus an acknowledgement bypass, not a policy failure

- **Files:** `gradle/japicmp.gradle:83-91`, `gradle/japicmp.gradle:107-109`, `gradle/japicmp.gradle:205-209`; policy source `SPEC.md:576-586`.
- **Evidence:** Any major mismatch selects `MAJOR`, then `failOnBreaking = !acceptBreaking`. With `-Pversion=2.0.0 -Pjapicmp.baseline=1.1.1 -Pjapicmp.acceptBreaking=true`, `:rpc-api:japicmp --rerun-tasks` printed `policy mode: MAJOR failOnBreaking=false` and completed successfully. The warning explicitly says it is off-policy, but does not fail the task.
- **Impact:** A `2.x` release can pass this supposed policy gate by supplying the acknowledgement. That directly contradicts “major (`1`) frozen, never increments”; an acknowledgement is allowed only for the minor breaking-change path.
- **Fix:** Reject a candidate/baseline whose major is not `1` during configuration or in a dedicated verification task, independently of compatibility results and `japicmp.acceptBreaking`. Keep acknowledgement handling exclusive to `MINOR`.

### F2 — P1: source-incompatible API changes pass every mode as compatible

- **Files:** `gradle/japicmp.gradle:55-65`, `gradle/japicmp.gradle:192-209`; contract `SPEC.md:593-600`.
- **Evidence:** `KrpcCompatPolicyRule` immediately returns `null` whenever `member.binaryCompatible` is true, and never reads source compatibility. `addDefaultRules = false` removes any plugin default that might otherwise catch it. Consequently a source-only break (for example, adding a checked exception while retaining the same JVM descriptor) produces no violation even in PATCH mode.
- **Impact:** The task is a binary-only gate despite its name, banner, CI job, and implementation document all claiming “binary/source-compat”. This permits a PATCH source break that §14.2 says must be checked.
- **Fix:** Treat `!member.sourceCompatible` as a breaking violation under the same PATCH/MINOR policy, using the plugin's source-compatibility violation facility (or a dedicated source rule). Add a red-first source-only fixture, plus a PATCH assertion that it fails.

### F3 — P2: broad package exclusions can silently remove public contract from coverage

- **Files:** `gradle/japicmp.gradle:118-130`, `gradle/japicmp.gradle:192-199`.
- **Evidence:** The filters exclude every class under `tech.krpc.internal.*` and `tech.krpc.common.proto.*`. Those packages currently contain public artifact types including `SerialEnum`, `InputProtoOrBuilder`, `OutputProtoOrBuilder`, `InputMarshaller`, `OutputMarshaller`, and `ProtoWriter`; production `rpc-client`/`rpc-server` code imports several `tech.krpc.internal.*` types. The claimed “consumers never import them” is not mechanically enforced, and a future public consumer utility placed in either namespace is silently unreviewed.
- **Impact:** The documented rationale is plausible for today's wire implementation, but package-wide exclusion is a permanent opt-out with no drift alarm.
- **Fix:** Replace the wildcard policy with an explicit, reviewed generated/wire type allow-list, or add a guard that fails when a new public type enters an excluded package (and when a covered public signature exposes an excluded type). Keep the intentional wire exclusion documented.

## Confirmed behavior and review evidence

- **Version parsing/mode:** `parseSemver` strips `-rc`, `-SNAPSHOT`, and build metadata before comparing numeric major/minor (`gradle/japicmp.gradle:69-89`). Thus `1.1.1` vs `1.1.0` is PATCH and `1.2.0` vs `1.1.x` is MINOR. A dev state at or below the baseline is deliberately classified PATCH (strictest). The parser is permissive for malformed numeric components, but valid suffixes are handled as claimed.
- **PATCH acknowledgement cannot leak:** Temporarily removing `RpcResult.orElseThrow()` and running `:rpc-api:japicmp -Pjapicmp.baseline=1.1.1 -Pjapicmp.acceptBreaking=true --rerun-tasks` printed `PATCH failOnBreaking=true acceptBreaking=true` and failed. The rich report named `Method tech.krpc.model.RpcResult.orElseThrow(): Is not binary compatible` and `Method has been removed`. The source file was restored exactly; a forced default rerun passed.
- **Baseline failure is loud:** `:rpc-api:japicmpBaselineDownload -Pjapicmp.baseline=9.9.9 --rerun-tasks` failed after both Central and Aliyun URLs failed, at `gradle/japicmp.gradle:179`; it did not skip comparison to green.
- **Timeliness:** `japicmp.baseline=1.1.1` is correctly present in `gradle.properties:74`, and forced comparison of both covered modules against downloaded Central `1.1.1` jars passed. This is the required baseline bump; it must stay in the change. The branch itself is based before the `1.1.1` release and displays `version=1.1.0`, whereas `origin/dev` has `version=1.1.1`; rebase/update before merge so branch CI represents the target version, then rerun the gate.
- **CI paths:** `.github/workflows/japicmp.yml:11-28` matches all `rpc-api/**` and `rpc-common/**` changes as well as Gradle wiring and `gradle.properties`. I did not independently run `actionlint` because it is not installed in this worktree.
- **Requested command:** `/opt/gradle/gradle/bin/gradle japicmpCheck --max-workers=2` returned `BUILD SUCCESSFUL`, but all japicmp tasks were `UP-TO-DATE`; it did not execute a comparison. A stronger forced run, `/opt/gradle/gradle/bin/gradle japicmpCheck --rerun-tasks --max-workers=2 --no-daemon`, executed both downloads, jars, and comparisons and returned `BUILD SUCCESSFUL`.

## Validation limits

- Ran the targeted Gradle checks above, including a missing-baseline negative test, a PATCH red-first probe, and a MAJOR acknowledgement probe.
- Did not run a live GitHub Actions job or actionlint (unavailable locally).
- `git show --check 944c8bc` and `git diff --check origin/dev..944c8bc` were clean. No production source change remains.
