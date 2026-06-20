# IMPL findings — KRPC-001 Phase 2: Quarkus 3.15.2 → 3.33 (omp)

> Owner: omp. Worktree `/Users/martin/Garden/wt-krpc-gradle9`, branch `feat/gradle9-bump`
> (stacks on Phase 1 `6b0f6cd`). Goal: `…/KRPC-001-P2-quarkus_GOAL.md`. Reviewer: codex.
> **Status: DONE — green, committed locally, not pushed. Re-review pending.**
> Toolchain: GraalVM JDK 21, Gradle 8.14.5.

## What changed

- `gradle.properties:35` → `quarkusMiniSupport=3.15.2` **→ `3.33.2`** (one line, the only edit).
  This property feeds BOTH the `io.quarkus` Gradle plugin version AND the
  `io.quarkus.platform:quarkus-bom` version. 3.33.2 is the latest 3.33.x LTS patch resolvable as
  both (the `3.33.1.1` / `3.33.2.1` entries are platform-only respins with no matching Gradle
  plugin, so they cannot be used for this dual-purpose property).
- **Nothing else.** No grpc/netty bump, no Gradle change, no language-level change, no native
  work. `application.properties` untouched; `quarkusBuild { nativeArgs { … } }` untouched
  (not forced — see below). The diff beyond Phase 1's wrapper is exactly `gradle.properties`.

## Prerequisite that unblocked this phase (done outside this worktree, by Martin)

The first 3.33 attempt failed at `:test-server:quarkusAppPartsBuild` because the prebuilt
extensions `tech.krpc.ext:ext-rpc` / `tech.krpc.ext:ext-mybatis` (1.0.0) used the legacy
class-based `@ConfigRoot` config style, which Quarkus 3.33 rejects (config roots must be
`@ConfigRoot` + `@ConfigMapping` interfaces). Those extensions were migrated to the new config
API and republished to mavenLocal, and the Gradle cache was cleared. `extRpcVersion` still
resolves 1.0.0 (the republished build), so no version bump was needed in this repo.

## What was verified (actually ran, on Gradle 8.14.5 / GraalVM JDK 21)

1. **Full compile + assemble incl. Quarkus augmentation** — `./gradlew clean build -x test`:
   ```
   > Task :test-server:quarkusGenerateCode
   > Task :test-server:quarkusAppPartsBuild
   > Task :test-server:quarkusDependenciesBuild
   > Task :test-server:quarkusBuild
   BUILD SUCCESSFUL in 18s
   89 actionable tasks: 88 executed, 1 up-to-date
   ```
   The 3.33.2 plugin ran on Gradle 8.14.5 (no Gradle-9 demand); BOM + all 3.33.2 extensions
   resolved; `test-server` and `rpc-server-quarkus` augment/assemble cleanly.

2. **DB-free core module tests** — `./gradlew :rpc-api:test :rpc-common:test :rpc-client:test
   :rpc-server:test :http-server:test`:
   ```
   BUILD SUCCESSFUL in 1s
   19 actionable tasks: 8 executed, 11 up-to-date
   ```
   (Same profile as Phase 1: these modules carry no executable JUnit tests — `main()`/harness/
   fixtures — so this confirms compile + test-task wiring on 3.33, not assertion coverage.)

3. **Runtime smoke against local MySQL (PASS)** — booted `test-server` via
   `:test-server:test --tests test.krpc.TestBookService` (a `@QuarkusTest`) with datasource
   overrides supplied as env vars (Gradle test workers inherit env; CLI `-D` does not reliably
   reach the forked worker) — `QUARKUS_DATASOURCE_JDBC_URL=jdbc:mysql://127.0.0.1:3306/example`,
   `QUARKUS_DATASOURCE_USERNAME=example`, `QUARKUS_DATASOURCE_PASSWORD=<via env, not committed>`:
   ```
   [t.k.m.r.b.QuarkusDataSourceFactory] (Test worker) : Bind QuarkusDataSource :<default>
   [i.quarkus] test-server 1.0.0.rc1 on JVM (powered by Quarkus 3.33.2) started in 2.624s.
   Installed features: [agroal, cdi, compose, ext-mybatis, ext-rpc, hibernate-validator,
                        jdbc-mysql, narayana-jta, smallrye-context-propagation]
   BUILD SUCCESSFUL in 10s
   ```
   → The app **starts on Quarkus 3.33.2** and the **datasource wires** (Agroal + jdbc-mysql +
   the custom `QuarkusDataSourceFactory` + migrated ext-rpc/ext-mybatis). TestBookService passed
   (its `BookMapper` is mocked, so no schema access was required) — so no missing-table issue
   surfaced. Datasource-wiring on 3.33 is confirmed working.

   Port note: the first smoke attempt failed only on `Failed to bind 0.0.0.0:50051` — that port
   is held by a separate, pre-existing Quarkus **3.15.2** dev-mode process (`order-server`,
   not part of this work). Left it running; re-ran with `RPC_SERVER_PORT=50061` (the
   `rpc.server.port` key) → clean start. Not a Quarkus-3.33 issue.

## nativeArgs (R3) — not forced, untouched

`quarkusBuild { nativeArgs { additionalBuildArgs = "-Ob" } }` in `test-server/build.gradle`
still configures cleanly on the 3.33 plugin (the build reached and passed augmentation without a
DSL error), so no migration was forced and none was made (scope discipline: no unforced edits).
The large commented `additionalBuildArgs2` Netty-substitution block is a comment and is left as-is.
**Open for Phase 4 native:** Martin's earlier intent was to migrate `-Ob` to
`quarkus.native.additional-build-args` to preserve intent; defer that to Phase 4 (native) since
it is not required by the 3.33 jump.

## Quarkus 3.33 migration notes (for the record)

- Resolved extension stack on 3.33.2: agroal, cdi, ext-mybatis, ext-rpc, hibernate-validator,
  jdbc-mysql, narayana-jta, smallrye-context-propagation, compose. smallrye-config is 3.16.0.
- `application.properties` keys in use (`quarkus.datasource.db-kind/username/jdbc.url`,
  `quarkus.hibernate-validator.fail-fast`, `quarkus.log.*`) all remain valid on 3.33 — no
  deprecated/removed key surfaced during augmentation or runtime boot.
- The only 3.33 breaking change that hit this project was the class→interface `@ConfigMapping`
  requirement for extension `@ConfigRoot`s, handled by the ext-rpc/ext-mybatis rebuild (above).

## Security note (from the blocked round — handled)

During the initial blocked attempt, a real `quarkus.datasource.password` leaked onto an
augmentation-worker command line because `QUARKUS_DATASOURCE_PASSWORD` was set session-wide in
the env; the Quarkus plugin forwards datasource config to workers as `-D`. This round began by
`unset QUARKUS_DATASOURCE_PASSWORD` (verified absent), and the only password used was the local
throwaway `youshallnotpass` (from the goal), passed via per-invocation env and scrubbed from
temp logs. No secret is in any tracked/committed file. Recommendation stands: prefer per-run
`-D`/env over a session-wide `QUARKUS_DATASOURCE_PASSWORD`, and rotate the previously-leaked
credential if it was real/shared.

## What was NOT verified

- Deep MyBatis integration tests that read/write real tables — `example` DB has no schema/tables
  loaded yet. TestBookService mocks its mapper so it did not exercise SQL. Real table-backed
  paths remain a **schema-load sub-task** (load DDL into `example`, then run table-backed tests).
- Native image build — out of scope (Phase 4).
- `test-server-spring` `@SpringBootTest` — unrelated to Quarkus; still env/network-gated (Phase 1
  finding), not part of this phase.

## Acceptance checklist (against the goal)

- [x] `quarkusMiniSupport` = 3.33.2; `./gradlew --version` still 8.14.5.
- [x] `./gradlew clean build -x test` green incl. test-server + rpc-server-quarkus augmentation.
- [x] DB-free core module tests green.
- [x] Runtime smoke: app starts on 3.33.2 + datasource wires (best-effort, PASS).
- [x] `git diff dev..HEAD --stat` beyond Phase 1 wrapper touches ONLY `gradle.properties`
  (+ this findings doc). No grpc/netty/Gradle/language edits; nativeArgs untouched (not forced).
- [x] No test deleted/skipped/weakened.

## Next

- Schema-load sub-task to exercise table-backed MyBatis paths (separate, env-gated).
- Phase 4 (native): migrate the `nativeArgs` `-Ob` to `quarkus.native.additional-build-args`.
- Awaiting codex re-review of this commit (stacked on Phase 1 `6b0f6cd`, local, not pushed).
