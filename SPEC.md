# KRPC Development Spec

A self-contained, tool-agnostic working spec for any coding agent (Claude, Codex,
or otherwise) contributing to KRPC. It distills *how to build, test, and ship*
this repo. It does **not** replace governance: architecture authority lives in
[`AGENTS.md`](AGENTS.md), the ADRs, module docs, and roadmap (see
[Source Of Truth](#source-of-truth)). When this spec and those conflict, those win.

## 1. What KRPC Is

Interface-first RPC for cloud-native services. You write a Java interface + DTOs;
KRPC handles transport, validation, metadata, and client generation. Normal
service authors write **no proto files**.

- Transport: gRPC / HTTP/2. Default payload encoding: JSON (broad client reach).
- Java interfaces and DTOs are the API source of truth.
- Discovery, load balancing, and telemetry are delegated to the platform
  (Kubernetes / Istio / deployment infra), not the runtime — see ADR-0001.
- Baseline: JDK 21; virtual threads are a supported runtime feature (ADR-0002).

## 2. Repository Layout

Gradle multi-module. Modules are declared in `settings.gradle`.

**Published to Maven Central** (group `tech.krpc`; each applies
`gradle/upload.gradle`):

| Module | Role |
| --- | --- |
| `rpc-api` | API contracts / annotations |
| `rpc-common` | shared runtime |
| `rpc-client` / `rpc-server` | core Java client / server runtime |
| `rpc-client-spring` / `rpc-server-spring` | Spring Boot integration |
| `rpc-server-quarkus` | Quarkus extension (+ native image support) |
| `http-server` | HTTP/gRPC-Web gateway |
| `ext-rpc-gen` | code generation (group `tech.krpc.ext`) |

**Not published** (test/demo only; `upload.gradle` is commented out):
`test-api`, `test-jwks`, `test-server`, `test-server-spring`.

External dependencies `tech.krpc.ext:ext-rpc` / `ext-mybatis` live in a **separate
repo** and are consumed from Central at `extRpcVersion` (`gradle.properties`).
They are not built here.

## 3. Build & Test

Use the pinned wrapper. Toolchain is reproducible (wrapper 8.14.5, checksum-pinned).

```bash
./gradlew build          # full build
./gradlew clean
./gradlew allDeps        # dependency report
```

- **JDK 21 is the baseline.** Public docs/examples must use `gradle` /
  `./gradlew`, never machine-specific Gradle paths.
- **Integration-test gotcha:** Quarkus/Spring integration tests in `test-server*`
  reach an internal MySQL host that is absent in clean/CI-less environments, so a
  bare `build` can hang on them. For local/agent builds that don't exercise the
  DB, run with `-x test`:

  ```bash
  ./gradlew clean build -x test
  ```

  When you skip tests, say so in completion notes and list what was not validated.

## 4. Dependency & Version Policy

- Versions are centralized in `gradle.properties`. Change them there, not inline.
- **grpc ↔ Netty invariant:** the app's Netty must be `>=` grpc's Netty, and a
  *single* Netty version must converge across the whole runtime graph (grpc +
  Quarkus BOM + Spring BOM). When bumping grpc, re-pin `nettyGrpcVersion` and
  verify no BOM downgrades it on any module's `runtimeClasspath`.
- Quarkus tracks an **LTS** line whose Gradle plugin matches the wrapper.
- Native image builds via container build on Mandrel (matching the Quarkus
  downstream); language level stays 21.

## 5. Wire Compatibility (hard rule)

KRPC is the **wire-compatibility source of truth** for all runtimes and clients
(Java, Spring, Quarkus, plus the TS/Python/Dart/Rust/C++ clients in sibling
repos). Any change to on-the-wire behavior (framing, encoding, metadata,
status/error mapping) is a cross-runtime contract change:

- Treat it as **REQUIRE APPROVAL** (§7) and back it with an ADR.
- `loss.md` documents byte-fidelity expectations (esp. native vs JVM `byte[]`
  round-trips); re-verify against it when touching serialization or native.

## 6. Conventions

- Interface-first: no hand-written proto for business APIs.
- Prefer evolving an existing module over adding a new one. Check a module's
  `FOR` / `NOT FOR` (in `docs/modules/`) before placing code; if it falls under
  `NOT FOR`, update the SoT first.
- Style: small cohesive files, explicit error handling, immutable updates over
  in-place mutation. Match surrounding code.
- Code comments only for durable architectural memory (constraints, security
  boundaries, wire-compat, migration logic, non-obvious tradeoffs) — e.g.
  `// ADR-0001: service discovery remains outside the runtime.` Don't comment
  obvious code.
- **Commits:** conventional-commit style; **never** add Claude/Codex/AI signature
  lines.

## 7. Work Modes

Use the lowest necessary mode (full definitions in `AGENTS.md`):

- **DO** — local, reversible, well-scoped; no public-contract / security /
  schema / wire changes. Just do it.
- **THINK** — multi-module, architecture tradeoffs, public contracts, new infra,
  migrations, possible SoT conflicts. State goal, impact, risks, plan, validation.
- **REQUIRE APPROVAL** — destructive migrations, deleting modules, auth/security
  boundary or production-config changes, force pushes / history rewrites,
  secret-handling changes, breaking wire compatibility, new platform capability.

## 8. Release (Maven Central)

Group `tech.krpc`. Publishing is scripted; credentials
(`OSSRH_BEARER_TOKEN`, `mavenCentral*`, `signing.*`) live in
`~/.gradle/gradle.properties` or env — **never** in the repo.

```bash
# build + sign + stage + upload as USER_MANAGED, wait for VALIDATED (reversible)
GRADLE_CMD=./gradlew gradle/publish-central.sh upload
GRADLE_CMD=./gradlew gradle/publish-central.sh status     # inspect deployment
# IRREVERSIBLE — publishes to Maven Central; a published version can never be
# deleted or overwritten. Requires explicit --yes.
GRADLE_CMD=./gradlew gradle/publish-central.sh publish --yes
```

- Set the release `version` in `gradle.properties` and add a `changelog.md` entry
  first.
- Two-stage by design: get to `VALIDATED`, confirm, then `publish --yes`. Tag
  (`vX.Y.Z`) and create the GitHub release **after** Central publish succeeds.

## 9. Redaction Boundary

KRPC has a public remote. Secrets/credentials and internal topology — **internal
IPs, hostnames, and infra addresses count as sensitive** — must never enter the
committed tree, logs, traces, or outbound messages. Keep them in local-only,
gitignored config or an external vault. Sweep before committing or pushing.

## Source Of Truth

Priority order (highest first); code is evidence of current behavior, not authority:

1. ADRs — `docs/decisions/`
2. Module docs — `docs/modules/` (`FOR` / `NOT FOR`)
3. Module evolution sections
4. Active roadmap — `docs/roadmap/active-roadmap.md`
5. Inline TODOs

Index: `docs/INDEX.md`. Governance entrypoint: `AGENTS.md`.
