# Project Engineering Guide

This file is the repository-level guide for AI coding agents working in KRPC.

## Project Overview

KRPC is an RPC framework built on gRPC/HTTP/2 with a focus on cloud-native simplicity.

Key boundaries read from the repository:

- Services are defined as Java interfaces and DTOs, without hand-written proto files for normal service authors.
- Service discovery, load balancing, and telemetry are delegated to Kubernetes, Istio, or deployment infrastructure.
- The repository contains API contracts, common runtime code, Java client/server runtime modules, framework/native-image integrations, HTTP gateway support, code generation, tests, and demos. The `rpcurl` CLI client is maintained externally in `krpc-crates` (Rust); this repo does not host an rpcurl implementation.

## Repo Facts (branches, releases, mirrors)

- Development and releases both happen on **`dev`** (the default branch). `main` is
  not the release branch, and the docs site deploys **only on pushes to `dev`**
  (`.github/workflows/docs.yml`, `docs-site/**` paths filter) — a docs-site fix
  landed on `main` will never deploy.
- `SPEC.md` and `skills/krpc/references/SPEC.md` are a byte-identical mirror pair:
  any change to one must update the other in the same commit.
- `ext-rpc-gen/` is a standalone-versioned artifact (`tech.krpc.ext:ext-rpc-gen`,
  version in its own `build.gradle`, not the root `gradle.properties`) and is
  excluded from krpc Central release bundles (duplicate-GAV guard); it releases
  separately.
- Central publishing runs through `gradle/publish-central.sh`
  (bundle → upload → status → publish; the publish step is irreversible).
- The test suite no longer needs a site-local database. The DB-backed `test-server`
  tests provision their own MySQL via Testcontainers
  (`test-server/src/test/java/test/krpc/db/MySqlTestResource.java`), so Docker is the
  only prerequisite; with Docker down they fail in seconds with an actionable message
  instead of hanging. In a Docker-less environment use `gradle build -x test`.
- **Known red — `:test-server-spring:test`.** `gradle build` still does not go green,
  for a reason unrelated to the database: `RpcClientAutoConfigure.autoRpcClientScannerConfigurer`
  is a non-static `@Bean` returning a `BeanDefinitionRegistryPostProcessor`, so Spring
  instantiates the config class before `@Autowired` processing and `afterPropertiesSet`
  NPEs on a null `Environment`. Making that method `static` fixes the NPE and then
  uncovers a second bug: `RpcServiceExposer.onApplicationEvent` blocks on
  `server.awaitTermination()`, so `SpringApplication.run()` never returns and any
  `@SpringBootTest` hangs forever. Both are in published modules and need a decision;
  until then run `gradle build -x :test-server-spring:test`.

## Source Of Truth Priority

When architecture sources conflict, use this priority order:

1. ADRs in `docs/decisions/`
2. Module docs in `docs/modules/`
3. Module evolution sections
4. Active roadmap in `docs/roadmap/active-roadmap.md`
5. Inline TODOs

Code is evidence of current behavior, but it is not the architecture authority. If code and docs conflict, state the conflict and choose whether to update code, update docs, or create a new ADR.

## Status Vocabularies

ADR status values:

- `proposed`
- `accepted`
- `deprecated`
- `superseded`

Roadmap and module evolution status values:

- `proposed`
- `active`
- `deferred`
- `obsolete`
- `rejected`
- `completed`

Do not mix these vocabularies. A rejected ADR should not remain as an ADR file; a rejected roadmap item should remain with rationale.

## Work Modes

Use the lowest necessary mode.

### DO

Use for local, reversible, well-scoped changes that do not change public contracts, security boundaries, module ownership, schemas, migrations, or framework direction.

### THINK

Use before changes involving multiple modules, architecture tradeoffs, public contracts, new infrastructure, migration paths, or possible source-of-truth conflicts. Include goal, relevant docs, impact, risks, tradeoffs, plan, and validation.

### REQUIRE APPROVAL

Stop for explicit approval before destructive migrations, deleting major modules, changing auth/security boundaries, production configuration changes, force pushes/history rewrites, secret-handling changes, large cross-module refactors, replacing an accepted architecture direction, or creating a new platform capability.

## Module Boundaries

Core module documentation lives in:

- `docs/modules/api-contracts.md`
- `docs/modules/runtime-core.md`
- `docs/modules/platform-integrations.md`

Before adding code to a module, check its FOR and NOT FOR sections. If the change falls into NOT FOR, do not place it there without updating the architecture source of truth.

## Before Adding A New Component

Ask:

- Is this a new capability or a variant of an existing capability?
- Can an existing component evolve instead?
- Does it duplicate an existing abstraction or create a parallel system?
- Is ownership clear?
- Is there an ADR, roadmap item, issue, or module evolution entry behind it?

Default to evolving existing components until there is concrete pressure for a new component.

Adding a boolean flag is the same question. Rules from the workspace-level **umbrella
ADR-0003 "Defaults encode correct behavior"** (distinct from this repo's ADR-0003) —
read it before choosing a default:

- The default must be the correct behavior. Do not ship a correctness or strictness
  fix as an opt-in switch. (Capability surfaces such as MCP are the separate opt-in
  class, and stay default OFF.)
- For a hand-written `System.getProperty` / `getenv` read: one accessor per flag,
  resolved lazily on the first-use path inside `try`/`catch`, logging its effective
  state once at INFO or above (WARN when it switches off a default-ON behavior;
  DEBUG is invisible in prod and so proves nothing). Never in a static or field
  initializer, and never copy an accessor's result into a static field —
  native-image bakes both in at build time, and a captured copy splits the switch
  (honoured on one path, ignored on another).
- Container-injected config (`@ConfigProperty`, `@Value`,
  `@ConfigurationProperties`) is exempt from the previous bullet **per read site,
  not per flag**: a flag injected in one place and hand-read in another is still
  bound for the hand-read part. Before calling a flag exempt, grep every read site
  of its name — `KRPC_MCP` is injected *and* hand-read in two handlers.
- The static-initializer half is gated: `FlagResolutionGateTest`
  (`flagsMustNotResolveInStaticInitializers`) in `arch-test`, ADR-0005
  freeze-ratchet, baseline only shrinks. It scans only the six `OnlyCoreModules`
  and does not see duplicate hand-written reads, reflection, or non-`System` config
  sources — green is not proof, and outside those modules nothing was even asked.

## Code Traceability

Add a short source reference in code only for durable architectural memory:

- architectural constraints
- security boundaries
- migration logic
- compatibility behavior
- non-obvious tradeoffs
- temporary exceptions or deprecated paths

Example:

```java
// ADR-0001: service discovery remains outside the runtime.
```

Do not comment obvious code.

## Tooling Preferences

- This is a Gradle multi-module Java repository.
- JDK 21 is the Java baseline.
- Virtual threads are a supported runtime feature, not a roadmap item.
- Local builds: use the repo wrapper `./gradlew` or a system `gradle`.
- Public documentation and examples must use `gradle`, not machine-specific Gradle paths.
- Prefer `rg` for search.
- Do not add Codex, Claude, or AI-generated signatures to commits.

Common commands:

```bash
gradle build
gradle test
gradle :http-server:test --tests "tech.krpc.http.server.GrpcWebCodecTest"
gradle :arch-test:test   # ARCH-001 architecture gate (ArchUnit, freeze-ratchet baseline; see ADR-0005)
gradle clean
gradle allDeps
```

## PR Self-Check And Push Gates

Before opening a PR, walk `docs/PR_SELF_CHECK.md` (every item traces to a real
incident). Machine-checkable items are enforced by `.githooks/pre-push` —
install once with `git config core.hooksPath .githooks`; CI re-runs the same
script on every PR (`selfcheck` workflow), so `--no-verify` only skips the local
reminder, not the gate.

## Validation And Completion

For non-trivial changes, completion notes must state:

- what changed
- what validation ran
- what was not validated
- relevant ADR, roadmap, issue, or module source of truth
- whether FOR / NOT FOR boundaries were touched
- whether an ADR, roadmap item, or module evolution status needs updating

Do not claim tests passed unless they actually ran.
