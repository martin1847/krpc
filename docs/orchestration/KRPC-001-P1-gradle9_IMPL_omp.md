# IMPL findings — KRPC-001 Phase 1: Gradle 8.0 → 8.14.5 (omp)

> Owner: omp. Worktree `/Users/martin/Garden/wt-krpc-gradle9`, branch `feat/gradle9-bump`.
> Goal (source of truth): `/Users/martin/Garden/middleware/krpc/docs/orchestration/KRPC-001-P1-gradle9_GOAL.md`
> (revised: 8.x stepping stone, Gradle 9 deferred behind Quarkus 3.33).
> Status: **Round 1 (codex REQUEST CHANGES) fixes applied — re-review pending.** Build evidence
> complete and green; wrapper now byte-matches official 8.14.5; `.gitignore` tightened. Decision A
> (track the wrapper) was approved by Martin and is implemented.
> Toolchain: GraalVM JDK 21 (`graalvm-jdk-21+35.1`) at `$JAVA_HOME`.

## What changed

- Added the Gradle wrapper at **8.14.5** (latest 8.x), regenerated **under 8.14.5 itself** so the
  jar/scripts byte-match the official distribution (see Round-1 fix B1). Produced on disk:
  - `gradle/wrapper/gradle-wrapper.properties` → `distributionUrl=…/gradle-8.14.5-bin.zip`
    + `distributionSha256Sum=6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854`
  - `gradle/wrapper/gradle-wrapper.jar` (sha256 `7d3a4ac4…f296172`, == official)
  - `gradlew`, `gradlew.bat`
- Un-ignored the wrapper in `.gitignore` (Decision A) using the standard form: re-include the
  `gradle/wrapper/` dir, re-ignore its contents (`gradle/wrapper/*`), then negate only the two
  official wrapper files; `gradlew*` overridden for the two root scripts (see Round-1 fix B2).
- **No other files changed.** No `build.gradle` edits, no `gradle.properties` edits, no plugin
  bumps, no source, no language-level, no Quarkus, no native. The 8.0→8.14.5 step within the
  8.x line forced **nothing** beyond the wrapper (sonarqube 3.4.0.2513 and kordamp jandex 2.0.0
  still load — deprecation warnings only, no hard failure).

## Ground-truth corrections to the goal's "Current state"

The goal asserts `gradle/wrapper/gradle-wrapper.properties` → `gradle-8.0-bin.zip`. **False in this
repo.** There is no committed/tracked wrapper at all, and none was on disk before this task:

- `.gitignore:17` → `wrapper/` ignores `gradle/wrapper/` (jar + properties).
- `.gitignore:54` → `gradlew*` ignores `gradlew` and `gradlew.bat`.
- `git ls-files` tracks no wrapper/gradlew files. Builds previously ran via the system `gradle` 8.5.

So this task **creates** a wrapper rather than bumping an existing tracked one.

## Decision A (approved) — wrapper now tracked

The wrapper was gitignored (`.gitignore:17 wrapper/`, `:54 gradlew*`), so initially it was invisible
to git. Martin approved **Decision A**: un-ignore and commit the wrapper for a reproducible pinned
1.0.0 toolchain. Implemented via the standard `.gitignore` form (see fix B2 below).

## Round 1 fixes (codex REQUEST CHANGES — 2 BLOCKING + 1 ADV)

- **B1 (BLOCKING) — wrapper not official 8.14.5.** The first commit generated the wrapper with the
  system `gradle` 8.5, so the jar carried 8.5's bytes (sha256 `d3b261c2…0d2bdd`). The wrapper jar is
  written from the *running* Gradle version, not the `--gradle-version` target. **Fix:** two-pass —
  the existing wrapper bootstrapped 8.14.5, then `./gradlew wrapper --gradle-version 8.14.5` ran
  *under 8.14.5* to rewrite jar/`gradlew`/`gradlew.bat` from the 8.14.5 distribution.
  **Verified:** `shasum -a 256 gradle/wrapper/gradle-wrapper.jar` →
  `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172` == official.
- **B2 (BLOCKING) — `.gitignore` negation too broad.** The first form re-included the whole
  `gradle/wrapper/` subtree (any new file there would be trackable). **Fix:** standard form —
  ```
  !gradle/wrapper/
  gradle/wrapper/*
  !gradle/wrapper/gradle-wrapper.jar
  !gradle/wrapper/gradle-wrapper.properties
  !/gradlew
  !/gradlew.bat
  ```
  **Verified:** `git check-ignore -q gradle/wrapper/evil.txt` → ignored (matches `gradle/wrapper/*`);
  `gradle-wrapper.jar`, `gradle-wrapper.properties`, `gradlew`, `gradlew.bat` → trackable.
- **ADV — pin distribution checksum.** Added
  `distributionSha256Sum=6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854` to
  `gradle-wrapper.properties` (written via `--gradle-distribution-sha256-sum`). Cross-checked against
  Gradle's published `gradle-8.14.5-bin.zip.sha256` (exact match). `./gradlew --version` → 8.14.5,
  confirming the wrapper still bootstraps with the checksum enforced.

## What was verified (actually ran, on 8.14.5 / GraalVM JDK 21)

1. **Wrapper resolves to 8.14.5**
   `./gradlew --version` → `Gradle 8.14.5`, `Launcher JVM: 21 (Oracle Corporation 21+35-jvmci-23.1-b15)`.

2. **Full compile + assemble of every module** — `./gradlew clean build -x test --console=plain`:
   ```
   BUILD SUCCESSFUL in 18s
   88 actionable tasks: 87 executed, 1 up-to-date
   ```
   All 13 subprojects compiled + assembled (incl. the Quarkus `:test-server` app jar and the
   Spring `:test-server-spring` boot jar). `-x test` per the env-adjusted acceptance (DB absent).

3. **DB-free core RPC module tests** — `./gradlew :rpc-api:test :rpc-common:test :rpc-client:test
   :rpc-server:test :http-server:test --console=plain`:
   ```
   BUILD SUCCESSFUL in 4s
   19 actionable tasks: 19 up-to-date
   ```
   All `test` tasks succeeded. **Honesty caveat:** these tasks executed **0 real JUnit tests** —
   every file under those modules' `src/test` is a `public static void main` runner / harness /
   generated proto fixture (`@Test` count = 0), and root `build.gradle:59` leaves `useJUnitPlatform()`
   commented. So "green" here = "compile + test-task wiring is clean on 8.14.5", **not** "assertions
   passed". The repo's only real assertions live in the env-gated modules below.

## What was NOT verified (env-gated — not a Phase-1 failure)

Both blocked by missing local infra, orthogonal to the Gradle bump:

- **`:test-server:test`** — `TestBookService` is `@QuarkusTest`; booting the Quarkus runtime
  initializes the Agroal datasource (`quarkus-jdbc-mysql` + `quarkus-agroal` + `ext-mybatis`)
  against `jdbc.host=mysql-junit.infra` (resolves to `198.18.1.24:3306`; no MySQL here). The
  test fork blocks at 0% CPU indefinitely — this is the original hang. The sibling `TestRef`
  (plain `@Test`, DB-free) is trapped in the same task, so the task as a whole is DB-gated.
- **`:test-server-spring:test`** — `DemoApplicationTests` is `@SpringBootTest`; booting the context
  (`rpc-server-spring` + `rpc-client-spring`, with a JWKS `https://…aliyuncs.com/…` URL and a gRPC
  client to `common-cdn.common:50051`) hung the worker fork on `-x :test-server:test`. No datasource
  in `application.yaml`, so the gate here is network/infra, not MySQL — still env-gated.

Module DB/infra classification (verified against each `build.gradle`, not just the given list):

| Module | Real JUnit tests? | DB/infra? | This run |
|---|---|---|---|
| rpc-api, rpc-common, rpc-client, rpc-server, http-server | none (main()/harness/fixtures) | no | verified (compile + test task green) |
| test-api, test-jwks, rpc-server-quarkus, rpc-client-spring, rpc-server-spring, ext-rpc-gen | none executable (main()/commented) | no | nothing to run |
| test-server | `TestBookService`(@QuarkusTest), `TestRef`(plain) | **MySQL** (agroal/jdbc-mysql/mybatis) | env-gated, NOT verified |
| test-server-spring | `DemoApplicationTests`(@SpringBootTest) | **network/context** (no datasource) | env-gated, NOT verified |

- **Native image** builds — not attempted (out of scope; no native work this phase).

## Deprecation warnings (carry into the later Gradle-9 phase)

`clean build` prints: *"Deprecated Gradle features were used in this build, making it incompatible
with Gradle 9.0."* `./gradlew help --warning-mode all` enumerates the real Gradle-9 hard-fail items
(these are what the actual Gradle-9 jump must fix, **in addition to** Quarkus 3.15→3.33):

- **`extendsFrom` on detached configurations** — *"will fail with an error in Gradle 9.0"* — emitted
  for `:rpc-api`, `:rpc-client`, `:rpc-common`, `:rpc-server`, `:rpc-server-quarkus`, `:test-api`,
  `:test-server` (`detachedConfiguration1` extending `compileClasspath`/`runtimeOnly`). Likely from
  `gradle/upload.gradle` / dependency-management interplay. **Hard blocker for Gradle 9.**
- **`ProjectDependency.getDependencyProject()`** — scheduled for removal in Gradle 9.0. **Hard blocker.**
- **`Project.exec(Closure)`** — scheduled for removal in Gradle 9.0 (the `git config …` metadata calls
  in `gradle/upload.gradle` / `ext-rpc-gen`). Use `ExecOperations`/`ProviderFactory.exec`. **Hard blocker.**
- **Space-assignment syntax** (`sourceCompatibility <value>`, `targetCompatibility`, `url`) — deprecated,
  removal in Gradle **10.0** (warning only on 9). Lower priority.

## Residual assumptions / risks

- `clean build -x test` and the whitelist run reused the daemon/cache; results are from a clean
  `clean build`, so compilation is fresh — not stale-cache artifacts.
- The "0 real JUnit tests in core modules" finding means Phase 1 cannot claim assertion-level test
  coverage on 8.14.5 from this environment; real assertions need the DB/infra (or a later DB-enabled
  CI run) to exercise `:test-server` / `:test-server-spring`.
- Network: the build resolves plugins/deps via mavenLocal → aliyun mirror → mavenCentral and
  downloaded the 8.14.5 distribution successfully, so outbound network is available; the env-gated
  hangs are specifically the MySQL endpoint and the spring-context startup dependencies.

## Acceptance checklist (against the revised goal)

- [x] `./gradlew --version` reports Gradle 8.14.5.
- [~] `./gradlew clean build` green — **adjusted per Martin**: full `clean build` cannot complete
  here (no MySQL → `:test-server` test fork hangs). Verified instead: `clean build -x test` green
  (all modules compile + assemble) + DB-free core-module `test` tasks green. test-server /
  test-server-spring tests are env-gated → NOT-verified.
- [x] `git diff dev..HEAD --stat` touches only wrapper files — Decision A implemented: `.gitignore`
  un-ignores the wrapper (standard form), so the diff now contains exactly `.gitignore`,
  `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew`,
  `gradlew.bat`, plus this findings doc. No source / dependency / language-level edits.
- [x] No test deleted, skipped, or weakened. (Test sources untouched; exclusions were task-level
  `-x` / whitelist invocation only, no edits to test code or assertions.)

## Next actions (await re-review)

1. Round-1 fixes (B1 byte-match, B2 gitignore form, ADV checksum) applied and re-committed locally
   on `feat/gradle9-bump` (no AI signature, not pushed).
2. Gradle 9 jump stays deferred until Quarkus is on 3.33; the deprecation list above is the
   pre-flight checklist for that phase.
