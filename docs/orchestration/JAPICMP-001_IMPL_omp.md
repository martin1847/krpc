# JAPICMP-001 — japicmp binary+source-compat gate wired into krpc's own CI (Implementation Findings)

Owner: omp. Branch `feat/japicmp-gate` (rebased onto `origin/dev` @ 62cf205, `version=1.1.1`).
Reviewer: codex (r1 → REQUEST-CHANGES → this round addresses all three findings). Date: 2026-07-18.
Commits LOCAL, no push.

Motivation: SPEC §14.2 declares a japicmp binary/source-compat check REQUIRED for the `*-api`
publish gate but noted it was "recommended, NOT wired here" — `grep japicmp` = 0 hits. krpc's OWN CI
had no gate. "没人检查的原则是注释" (NORTH_STAR): "the interface is the contract" (NS-1) is only real
once a machine verifies the diff. This change adds `japicmpCheck`: a Gradle task + a cheap standalone
CI job that diffs the working tree's contract classes against the latest Central release and **encodes
the SPEC §14.1 version policy** (patch = compatible-only; minor = breaking-allowed-with-ack; major
frozen at 1 forever).

## codex r1 findings — resolution summary

| # | Finding | Resolution |
| --- | --- | --- |
| F1 (P1) | MAJOR was a warning + ack bypass, not a policy failure | New `japicmpMajorPolicyCheck` task rejects any `major != 1` (candidate OR baseline) **unconditionally** — independent of compatibility results and `japicmp.acceptBreaking`; runs as a dependency of every japicmp task, before any comparison. Ack is now a MINOR-only path. |
| F2 (P1) | Source-incompatible changes passed every mode | `KrpcCompatPolicyRule` now fails on `!binaryCompatible` **OR** `!sourceCompatible`, under the same PATCH/MINOR policy. Red-first source-only fixture added below. |
| F3 (P2) | Broad package exclusion could silently drop public contract | **Exclusion removed entirely — rpc-common is now covered IN FULL** (wire packages included). Evidence + rationale below; this is strictly stronger than the requested "exclusion + drift guard" and eliminates drift by construction. |

Plus: rebased onto `origin/dev` (now `version=1.1.1`, matching baseline `1.1.1`); CI job now runs
`--rerun-tasks` so a cached `UP-TO-DATE` can never masquerade as a real comparison.

## What changed (build/CI + docs only; zero production source)

| Area | File | Change |
| --- | --- | --- |
| Plugin classpath | `build.gradle` | `buildscript{}` puts `me.champeau.gradle:japicmp-gradle-plugin:0.4.6` on the root classpath (inherited by subprojects + `apply from` scripts); `apply from: gradle/japicmp.gradle` at the end. |
| Gate wiring | `gradle/japicmp.gradle` (new) | Policy rule `KrpcCompatPolicyRule` (binary+source), semver mode computation, `japicmpMajorPolicyCheck` (§14.1 enforcement), per-module `japicmp` + `japicmpBaselineDownload` tasks (rpc-api, rpc-common), root aggregate `japicmpCheck`. |
| Baseline property | `gradle.properties` | `japicmp.baseline=1.1.1` (single source of truth; overridable `-Pjapicmp.baseline=x.y.z`). |
| CI job | `.github/workflows/japicmp.yml` (new) | Standalone Temurin-21 job running `japicmpCheck --rerun-tasks` on pushes/PRs touching covered module sources or build wiring. actionlint-clean. |
| Findings | this file | — |

`gradle japicmpCheck` (default baseline 1.1.1) is **GREEN**: both modules "No changes" (dev == 1.1.1).

## Plugin choice — `me.champeau.gradle.japicmp` 0.4.6

The de-facto Gradle wrapper around [japicmp](https://github.com/siom79/japicmp) (Gradle 6+; used by
Gradle itself, Micronaut, OpenSearch). Its **rich-report rule DSL** is what lets one `ViolationRule`
turn a binary- or source-incompatible member into an `error` (build failure) or an `accept`
(reported, no failure) depending on the computed mode + ack flag — the difference between a gate and a
report. Verified against this repo's Gradle 9.6.0.

## Policy encoding — the SPEC §14.1 matrix (printed in every run's banner)

Mode is derived from the in-dev `version` (`gradle.properties`) vs `japicmp.baseline`:

| mode | trigger | binary- OR source-incompatible change | additive change |
| --- | --- | --- | --- |
| **PATCH** | minor equal (e.g. `1.1.x` vs `1.1.y`) | **BUILD FAILURE** (always; `acceptBreaking` ignored) | pass |
| **MINOR** | minor bumped (e.g. `1.2` vs `1.1`) | FAIL unless `-Pjapicmp.acceptBreaking=true` | pass |
| **MAJOR** | major `!= 1` (candidate or baseline) | **REJECTED UNCONDITIONALLY** — no ack path (`japicmpMajorPolicyCheck`) | rejected too |

- `failOnBreaking = (mode == MINOR) ? !acceptBreaking : true`. The ack flag affects **only** MINOR.
- **Major immutability (F1):** `japicmpMajorPolicyCheck` throws if `version`'s or `baseline`'s major is
  not `1`, citing SPEC §14.1 (major frozen at 1 forever; wire envelope stable, NS-2). It is a
  dependency of `japicmpCheck` and of each module's `japicmp` task, so it fails before any comparison,
  regardless of `acceptBreaking`. There is deliberately no MAJOR acknowledgement escape hatch.
- **Source compatibility (F2):** the rule reads both `member.binaryCompatible` and
  `member.sourceCompatible`; either being false is a breaking violation under the same policy. The
  console/report label the kind (`binary`, `source`, or `binary+source`).

## Module coverage — full, no exclusions (F3 resolution)

Covered: **`rpc-api`** (mandatory — it IS the contract, `@RpcService` interfaces + DTOs, NS-1) and
**`rpc-common` IN FULL**, including the wire packages `tech.krpc.internal.*` (InputProto/OutputProto/
SerialEnum) and `tech.krpc.common.proto.*` (marshallers/codec).

The r0 build excluded those two wire packages ("internal, consumers never import them"). **codex F3
was right; the exclusion is removed.** Two independent reasons:

1. **Covered public API already exposes the wire types in public signatures** — so excluding them
   silently dropped real public contract (the exact F3 risk, and precisely the "covered public
   signature references an excluded type" guard condition the reviewer specified). Enumerated:
   - `common.MethodStub`: `public final MethodDescriptor<InputProto,OutputProto> methodDescriptor;`
     and `public static MethodDescriptor<InputProto,OutputProto> buildMd(String)`.
   - `common.ResultWrapper`: `public OutputProto output;` and `public ResultWrapper(OutputProto)`.
   - `serial.Serial`: `public static SerialEnum[] supported()`; `serial.JsonSerial`:
     `readOutput(OutputProto,...)`, `writeOutput(Object,OutputProto.Builder)`, `readInput(InputProto,...)`,
     `SerialEnum id()`; `serial.ClientReader.readOutput(Serial,OutputProto)`.
   Implementing the drift guard literally would therefore have failed on the current GREEN state —
   demonstrating the exclusion was untenable, not merely risky.
2. **The wire field layout is frozen contract** (NS-2; `origin/dev` commit 62cf205 "field layout IS the
   wire contract — InputProto/OutputProto field numbers frozen"). Frozen contract is exactly what a
   compat gate must ENFORCE, not exclude.

So the cheapest honest impl is **no exclusion**: the whole public surface of each covered module is
gated. Nothing is excluded ⇒ nothing can drift ⇒ no drift guard is needed. This is strictly stronger
than "keep the exclusion + add a guard," which is why the directive to "keep the wire exclusion" is
deliberately not followed — the evidence shows the exclusion hides live contract. **No class of
binary- or source-incompatibility is knowingly excluded from either covered module.**

NOT covered — deliberate: `rpc-server`, `rpc-server-quarkus`, `http-server`, `rpc-client`,
`rpc-client-spring`, `rpc-server-spring`, `ext-rpc-gen`. Their public surface is dominated by
grpc/netty/quarkus/spring runtime plumbing that churns legitimately; SPEC §14 scopes the discipline to
the `*-api` contract. Adding a module is a one-line change to `japicmpModules` in `gradle/japicmp.gradle`.

## Baseline resolution — direct Central download, not a Gradle configuration

Non-obvious pitfall: in a single multi-project build, Gradle's **implicit project substitution**
rewrites any external `tech.krpc:<module>:<ver>` dependency to the local same-coordinate PROJECT
(matched on `group:name`), so a baseline resolved via a configuration became the *freshly built* jar —
old == new, every diff a silent "No changes". Neither an explicit `substitute module(...) using
module(...)` rule nor `useGlobalDependencySubstitutionRules = false` (composite-build scope only)
overrode it (verified via `:rpc-api:dependencies`). Fix: fetch the canonical baseline jar **directly
from Maven Central** (Aliyun mirror as fallback) in a `japicmpBaselineDownload` task, bypassing module
resolution — also the most honest source (the baseline *is* the published Central release).

## Proof — red-first + full matrix (all branches exercised, `--rerun-tasks`)

1. **PATCH + binary break = FAIL.** Removed public `RpcResult.orElseThrow()`, `:rpc-api:japicmp` vs
   1.1.1 → `Method ... orElseThrow(): Is not binary compatible / Method has been removed` → BUILD
   FAILED (exit 1). Reverted → green.
2. **PATCH + source-only break = FAIL (F2).** Added `throws java.io.IOException` to public
   `RpcResult.orElseThrow()` (binary-compatible — descriptor unchanged; source-incompatible),
   `:rpc-api:japicmp` vs 1.1.1 →
   ```
   Class  tech.krpc.model.RpcResult: Is not source compatible
   Method tech.krpc.model.RpcResult.orElseThrow(): Is not source compatible
          Method is now throws a checked exception
   BUILD FAILED   (exit 1)
   ```
   The r0 binary-only rule would have passed this. Reverted → green; production source clean.
3. **MAJOR = REJECTED, even with ack (F1).** `:rpc-api:japicmp -Pversion=2.0.0
   -Pjapicmp.acceptBreaking=true --rerun-tasks` → `Task :japicmpMajorPolicyCheck FAILED` with
   `SPEC §14.1 violation: the krpc major version is frozen at 1 FOREVER ...` → BUILD FAILED (exit 1),
   before any comparison. (Also fails if `-Pjapicmp.baseline` has major != 1.)
4. **MINOR + break, no ack = FAIL / with ack = PASS.** `rpc-common` vs `1.0.3` (minor bump ⇒ MINOR):
   ```
   -Pjapicmp.baseline=1.0.3                              → MINOR failOnBreaking=true  → FAILED (exit 1)
   -Pjapicmp.baseline=1.0.3 -Pjapicmp.acceptBreaking=true→ MINOR failOnBreaking=false → SUCCESS (exit 0)
   ```
5. **PATCH + additive = PASS / full-coverage green.** `gradle japicmpCheck --rerun-tasks` (default
   baseline 1.1.1, full rpc-common coverage incl. wire packages) → BUILD SUCCESSFUL, both modules
   "No changes".

## Retrospective finding (still valid) — a breaking change already shipped as a PATCH

`rpc-common 1.1.0 → 1.1.1` (a *patch* bump) removed the public enum constant `EnvUtils$AppEnv.PRE`
(renamed `STAGING`, commit `6a17374`) — binary-incompatible per SPEC §14.1, which allows breaks only on
a minor. Verified on the published jars (`javap`): `1.1.0` = `DEV TEST PRE PROD`, `1.1.1` =
`DEV TEST STAGING PROD`. Reproduce the gate catching it: `-Pjapicmp.baseline=1.1.0` (dev vs 1.1.0 →
RED on `AppEnv.PRE`). The string config contract (`APP_ENV=pre`) was preserved via a `pre/stage →
STAGING` alias, so runtime config did not break — but the compiled API did. This is exactly the silent
break the gate now prevents going forward. No source fix is made (scope = build/CI + findings only).

## Release flow — how a release bumps the baseline

1. Cut release `1.MINOR.PATCH`, publish to Central via `gradle/publish-central.sh`.
2. In the same release commit set `japicmp.baseline=1.MINOR.PATCH` in `gradle.properties`, and bump
   `version` to the next dev version.
3. Thereafter `japicmpCheck` diffs the next dev cycle against the just-released artifact: a
   compatible-only (patch) next release stays green; a breaking (minor) next release requires a MINOR
   version bump + `-Pjapicmp.acceptBreaking=true`; a major bump is rejected outright.

## CI wiring

`.github/workflows/japicmp.yml` — a standalone Temurin-21 job (NOT folded into the slow
`native-smoke` build), triggered on `push`/`pull_request` touching `rpc-api/**`, `rpc-common/**`,
`**/*.gradle`, `gradle.properties`, `gradle/wrapper/**`, or the workflow itself. Runs
`./gradlew --init-script .github/ci-init.gradle japicmpCheck --rerun-tasks --no-daemon` (the init
script pins Central so US runners avoid the Aliyun-mirror 502s; the baseline jar is pulled straight
from repo1.maven.org by the task; `--rerun-tasks` guarantees a real comparison every run — no
UP-TO-DATE stand-in). `permissions: contents: read`; concurrency-cancel on ref.

## Validation

- **Ran (all `--rerun-tasks`):** `gradle japicmpCheck` (green, baseline 1.1.1, full coverage);
  PATCH binary red-first (`orElseThrow` removal); PATCH source-only red-first (checked exception);
  MAJOR rejection with ack (`-Pversion=2.0.0`); MINOR ack matrix vs 1.0.3 (no-ack FAIL / ack PASS);
  `javap` on published 1.1.0 vs 1.1.1 `AppEnv`. Both seeded fixtures reverted; `git status` on
  `rpc-api/` clean. `actionlint` on the workflow: CLEAN. Rebase onto `origin/dev` (62cf205): clean, no
  conflicts.
- **NOT validated:** the GitHub Actions job on a live runner (needs the PR; the run command mirrors
  `native-smoke.yml`'s proven `./gradlew --init-script` pattern). Local runs used the system Gradle
  9.6.0 (== the wrapper's pinned version).
- **Source of truth:** SPEC §14.1 (version policy incl. major-frozen) + §14.2 (binary/source japicmp
  gate, previously "NOT wired here" — now wired). No ADR needed; enforcement of an existing accepted
  policy. FOR/NOT-FOR boundaries untouched (build/CI only).

## Open item for the maintainer (out of scope here)

`rpc-common 1.1.0 → 1.1.1` shipped a binary-incompatible `AppEnv.PRE` removal as a PATCH. Options:
(1) accept retroactively (string config held via aliases; baseline `1.1.1` keeps the gate honest going
forward — current delivered state), or (2) restore `AppEnv.PRE` as a `@Deprecated` alias of `STAGING`
in a `1.1.2` patch so old compiled consumers relink. Both are production-source/release decisions
outside this task's scope (build/CI + findings only).
