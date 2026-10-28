# SPEC-DOCS-001 — SPEC/skill docs train (Implementation Findings)

Owner: omp. Worktree `wt-specdocs`, branch `docs/spec-docs-001` (from origin/dev @ 5e5b120).
Reviewer: codex (docs-honesty). Date: 2026-07-17. **DOCS-ONLY** — zero production/test code
changes. Commits LOCAL, no push.

## What changed

| file | change |
| --- | --- |
| `SPEC.md` | +§14 Contract evolution, +§15 Consumer guide, +§16 Operations facts; old §14 checklist renumbered → §17 |
| `skills/krpc/references/SPEC.md` | byte-identical mirror (`cp SPEC.md …`; skill-sync CI = `diff -q`) |
| `skills/krpc/SKILL.md` | added SPEC §14/§15/§16 pointers + the "method names are contract, not guessable" standing instruction (RPCURL-001) |
| `docs/orchestration/SPEC-DOCS-001_IMPL_omp.md` | this findings doc |

No `.java`, no test, no build file, no ADR touched.

> **r2 (fix round):** all r1 review findings (`SPEC-DOCS-001_REVIEW_codex_r1.md`) addressed;
> every anchor below re-verified against the working tree. Status tags now distinguish
> code-anchored facts from external recommendations, conventions, and inferences. See the
> "r2 fixes" log near the end.

## Claim → anchor table

Status legend: **VERIFIED** = code-anchored in THIS repo; **CONVENTION** = authoring
guidance, not serializer/code-enforced; **EXTERNAL** = operational recommendation with no
in-repo anchor (labelled as such in SPEC, NOT presented as verified behavior); **INFERENCE**
= reasoned from mechanism, not test-pinned. Anchors re-verified at r2.

### §14 Contract evolution (SPEC-CONTRACT-001)

| claim | anchor | status |
| --- | --- | --- |
| `version` = `1.MINOR.PATCH`, group `tech.krpc`, major frozen at `1` | `gradle.properties:4-5` | VERIFIED (value); major-frozen = stated policy (NS-2) |
| minor = breaking / patch = compatible; superseded line → security-only | `docs/support-policy.md:9-18` | policy, doc-anchored |
| japicmp `*-api` gate (breaking fails a patch publish; additive passes) | none in-repo — grep `japicmp` = 0 hits; reference impl in a downstream consumer CI (not this tree) | EXTERNAL — SPEC §14.2 now labelled "NOT wired in this repo" |
| Central publish path | `SPEC.md §12` (`gradle/publish-central.sh`) | pre-existing cross-ref |
| deploy-window rule (breaking front+back same window) | field report (staging outage); wire-change rationale `ADR-0003:47-55`, NS-2 | EXTERNAL — SPEC §14.3 labelled "field report; not a krpc-code fact" |

### §15 Consumer guide (SPEC-CONSUMER-001)

| claim | anchor | status |
| --- | --- | --- |
| Bearer first, then cookie `access-token`; cookie name = `DEFAULT_COOKIE_NAME` | `rpc-server/.../jws/JwsVerify.java:49` (const), `rpc-server/.../ServerContext.java:137-144` (selection: `bearerToken` → `cookieToken` → `verify`) | VERIFIED (anchors corrected at r2 from :33 / :125-131) |
| rpcurl `-c/--cookie` → raw `cookie` header; business form `access-token=<jwt>` | `rpcurl/java-rpcurl/src/main/java/tech/krpc/RpcUrl.java:53-54` (opt), `:63-64` (`headers.put("cookie", cookie)`) | VERIFIED (package path added at r2) |
| rpcurl `-t/--token` → `authorization: Bearer <jwt>` | `rpcurl/.../RpcUrl.java:56-57` (opt), `:67-68` (`"Bearer "+token`) | VERIFIED |
| **no `KRPC_TOKEN` env var** | grep `KRPC_TOKEN` repo-wide = 0 hits | VERIFIED absence — field report was WRONG; SPEC states no such env |
| agent HTTP surface forwards only `Authorization`, not `Cookie` | `docs/agent-guide.md:41-48` | VERIFIED (pre-existing doc) |
| name derivation: `I`/`Service`/`Rpc` stripping; `@UnsafeWeb` prefixless | `RefUtils.java:179-190` | VERIFIED |
| **hidden route** = `-` prepended to the WHOLE `{app}/{Service}` → name `-{app}/{Service}`, gRPC path `-{app}/{Service}/{method}`; CLI mirror `rpcurl --no-web` | `RefUtils.java:192-196` (`HIDDEN_SERVICE + (app/Service)`), `:171` (const); `RpcUrl.java:97-99` (`--no-web` prepends `-` to app) | VERIFIED — r1 said `-{Service}` / `{app}/-{Service}`; CORRECTED (finding 1) |
| agent lookup key = literal `"Service/method"`; unknown/hidden → `code:5` | `WebMethodRegistry.java:36-40`; `docs/agent-guide.md:171-184` | VERIFIED |
| serializer = `NON_NULL` out, lenient in | `JsonUtils.java:28-29` | VERIFIED |
| date=`YYYY-MM-DD` / datetime=ISO-8601-zone / money=`Long` cents / large-id=`String` | not serializer-enforced (`JsonUtils.java:31-38` never disables `WRITE_DATES_AS_TIMESTAMPS`); id-as-String `Img.java:26`; `java.time` off wire `TimeResult.java:23-25` (commented) | CONVENTION (SPEC §15.3 frames as such) |
| INVALID_ARGUMENT — jakarta validation carries `field=value(constraint)` | `ValidatorInvoke.java:35-41` | VERIFIED (anchor corrected from :37-40) |
| INVALID_ARGUMENT — malformed JSON is BARE (`<traceId>,malformed JSON request body`, no field) | `UnaryMethod.java:243-249` | VERIFIED — split from validation at r2 (finding 4) |
| UNAUTHENTICATED / PERMISSION_DENIED give no field hint | `JwsVerify.java:401,485,448,454` | VERIFIED; gap named externally as RPCURL-001 (not tracked in-repo) |
| Unimplemented/unknown = bare | gRPC `UNIMPLEMENTED` (grpc-java stock); HTTP `WebMethodRegistry.java:36-40` → `code:5` | VERIFIED |
| `-rc` routes + never-shadows-GA ordering | `mavenLocal()`/`MAVEN_REPO` scaffold `build.gradle:16-19`; Maven qualifier ordering + Nexus route | mavenLocal/file-repo VERIFIED; ordering + Nexus = EXTERNAL (consumer-side, SPEC §15.5 labelled) |
| living example = LH `scripts/staging-smoke.sh` | field report (external, LH-owned) | EXTERNAL — pointer only, NOT vendored |

### §16 Operations facts (SPEC-OPS-001)

| claim | anchor | status |
| --- | --- | --- |
| general build-time-baked vs runtime-flippable config rule | `benchmark/RESULTS.md:24-29` (build-bake concern), `:93-104` (runtime-flip proof) | VERIFIED (general rule + the defaultExecutor proof) |
| **`rpc.client.*.url` runtime-overridable** | none in-repo — no production key, no Quarkus RUN_TIME root, no native override test; fix lives in `ext-rpc` repo (out of tree). `RESULTS.md:24-29` is the historical build-bake, NOT a fix | UNVERIFIED HERE — SPEC §16.1 rewritten as ext-rpc-scoped/unverified (finding 2; r1 wrongly said RUN_TIME VERIFIED) |
| how-to-verify = same-binary env flip (proven for `rpc.server.defaultExecutor`) | `benchmark/RESULTS.md:93-104` (`RPC_SERVER_DEFAULTEXECUTOR`) | VERIFIED (for that key only) |
| gRPC port default 50051 | `RpcConstants.java:31` | VERIFIED |
| krpc HTTP face default 8080 (own netty server); collides with Quarkus REST 8080 | `HttpHandlerExpose.java:36`, `HttpServer.java:32`, `:86`; Quarkus default external | VERIFIED (krpc side); field report said 8088 — CORRECTED |
| spans since OTEL-001: gRPC SERVER/CLIENT + HTTP face | `RpcServerBuilder.java:145-146`, `OtelServerInterceptor.java:44-54`, `MethodCallProxyHandler.java:61-64`, `AbstractHttpHandler.java:181-236` | VERIFIED |
| MDC↔span coexist, one traceparent, W3C-only | `OtelServerInterceptor.java:75`; ADR-0006 "Coexistence"; ADR-0003 | VERIFIED (doc+code) |
| empty `traceId=`/`spanId=` = no VALIDLY-PARSED traceparent (absent OR malformed); raw header still in MDC | `ServerContext.java:96-105`, `:99` (raw stored); `TraceMeta.java:39-47` (null on absent/malformed) | VERIFIED — over-absolute r1 diagnosis CORRECTED (finding 3) |
| zero-trace fault tree ①spans ②egress NetworkPolicy ③receiver auth | ① `OtelServerInterceptor.java:40-42` + ADR-0006 wiring; ②③ platform (NS-3, no repo anchor) | ① VERIFIED; ②③ EXTERNAL ops runbook (generic) |
| unknown gRPC method produces no span | `RpcServerBuilder.java:145-146` (global intercept), `OtelServerInterceptor.java:44-54` (span keyed on resolved descriptor); grpc-java dispatch step has no in-repo test / no pinned grpc-java source | INFERENCE — SPEC §16.4 now labelled `[inference, not test-pinned]` (finding 7) |
| release: image-baked gated by manual bump; rollback respects Flyway floor | `SPEC.md §12`; Flyway floor = consumer/`ext-mybatis` (ADR-0002/NS-5, §12.6) | EXTERNAL runbook; persistence posture doc-anchored |

## The 3 advertised corrections (field report vs code)

1. **`KRPC_TOKEN` does not exist.** grep = 0 hits. SPEC §15.1 documents only the real
   `-t`/`--token` flag and states there is no such env var.
2. **krpc HTTP port default is 8080, not 8088.** `HttpHandlerExpose.java:36`,
   `HttpServer.java:32`. `8088` appears nowhere; it is offered only as an example
   deconfliction reassignment. Quarkus REST also defaults to 8080 → the real collision.
3. **date/datetime/money/large-id are DTO conventions, not serializer behavior.**
   `JsonUtils.java:31-38` registers `JavaTimeModule` if present but never disables
   `WRITE_DATES_AS_TIMESTAMPS`, so raw `java.time` serializes numeric. SPEC §15.3 frames
   all four as authoring conventions.

## r2 fixes (this round — all 7 review findings)

1. **Finding 1 (hidden route):** SPEC §15.2 row + derivation corrected to
   `-{app}/{Service}/{method}` (the `-` prefixes the whole `{app}/Service` value —
   `RefUtils.java:192-196`), with the `rpcurl --no-web` CLI mirror (`RpcUrl.java:97-99`).
   Findings row above corrected.
2. **Finding 2 (`rpc.client.*.url`):** removed the RUN_TIME assertion; SPEC §16.1 now
   describes it as ext-rpc-scoped and UNVERIFIED in this repo (the cited `RESULTS.md:24-29`
   is the historical build-bake, not a fix). No in-repo anchor fabricated.
3. **Finding 3 (empty MDC):** SPEC §16.3 now says empty IDs = no validly-parsed W3C
   traceparent (absent OR malformed — `TraceMeta.java:39-47`), and to inspect the raw
   `MDC_TRACEPARENT` (`ServerContext.java:99`) first.
4. **Finding 4 (INVALID_ARGUMENT):** SPEC §15.4 table split into jakarta-validation
   (`ValidatorInvoke.java:35-41`, field-level) vs malformed-JSON (`UnaryMethod.java:243-249`,
   bare description).
5. **Finding 5 (stale anchors):** re-verified all `file:line` against the working tree —
   corrected `JwsVerify` cookie const `:33`→`:49`, selection `ServerContext:125-131`→
   `:137-144`, `ValidatorInvoke:37-40`→`:35-41`; added source-relative package path for
   `rpcurl/.../RpcUrl.java` in SPEC §15.1/§15.2.
6. **Finding 6 (field-report-only material):** §14.2 japicmp, §14.3 deploy-window, §15.5
   Nexus/Maven-ordering, §15.6 LH script, and the RPCURL-001 mention (SPEC + SKILL) are now
   labelled EXTERNAL operational recommendations; nonexistent in-repo anchors are no longer
   called "queued follow-ups". This findings table no longer claims every row is a code fact.
7. **Finding 7 (unknown-method/no-span, advisory):** SPEC §16.4 rephrased as
   `[inference, not test-pinned]` — no krpc test and no pinned grpc-java source anchor the
   dispatch premise.

## Remaining follow-ups (external / out of scope, NOT in-repo tracked items)

- A native env-override integration test for a runtime client-URL, once a concrete runtime
  consumer exists (the behavior lives in the `ext-rpc` repo, not this tree).
- Wiring a japicmp `*-api` compatibility gate into krpc's own release
  (`gradle/publish-central.sh`); today it exists only in a downstream consumer CI.
- rpcurl runtime introspection (external id RPCURL-001).

These are named honestly as external/proposed; none is a tracked roadmap/ADR/issue item in
this repository.

## Validation

- **Mirror byte-identical:** `diff -q SPEC.md skills/krpc/references/SPEC.md` → empty (same
  command as `.github/workflows/skill-sync.yml:17`). PASS.
- **`gradle :arch-test:test` canary (docs-only should not affect it):** `BUILD SUCCESSFUL`, 22 tasks up-to-date (Gradle 9.6.0 / GraalVM JDK 25). PASS.
- **`git status -s` (docs/skill only):** 4 modified — `SPEC.md`, `skills/krpc/references/SPEC.md`, `skills/krpc/SKILL.md`, this findings doc. The reviewer's `SPEC-DOCS-001_REVIEW_codex_r1.md` is present untracked (codex's artifact; not part of this commit). No code/test/build files touched; jdtls killed worktree-scoped. PASS.

## Boundaries / SoT

- No ADR contradicted. §16.3/§16.4 track ADR-0003 (W3C) as amended by ADR-0006 (span
  creation). Persistence rollback note defers to ADR-0002 / NS-5 (SPEC §12.6).
- No module FOR/NOT-FOR boundary touched (docs-only).
- No status vocabulary changes; RPCURL-001 / EXTRPC-URL-001 are external follow-up ids, not
  created as ADRs/roadmap entries here.

## r3 — condensing pass (maintainer review of PR #24: "SPEC too long, brief it")

Wording-only tightening; **zero fact loss, zero anchor loss** (compressed prose → tables /
one-line rules, merged restatements, cut rationale-storytelling to load-bearing
parentheticals). No claim removed; every `file:line` anchor retained. Primary target = the
r1/r2 additions §14–§16; §1–§13 left untouched (long-standing contract wording cited by
code/ADRs — not restructured). Narratives converted: §16.3 zero-trace troubleshooting →
3-row fault tree; §16.2 probe anecdote → one line; §14.3 deploy rationale → one-line why.

**Line-count delta (header → line before next header, same `awk` both sides):**

| section | before (HEAD e77ac0a) | after | Δ |
| --- | --- | --- | --- |
| §14 Contract evolution | 60 | 49 | −11 (−18%) |
| §15 Consumer guide | 164 | 132 | −32 (−20%) |
| §16 Operations facts | 128 | 99 | −29 (−23%) |
| §17 Checklist | 12 | 12 | 0 (already minimal) |
| **§14–§17 total** | **364** | **292** | **−72 (−20%)** |
| whole file | 1124 | 1052 | −72 (all in §14–§17) |

**On the ~50% target vs achieved ~20%:** the material is anchor-dense reference (tables +
one-anchor-per-bullet); a literal 50% wording cut cannot coexist with rule-1 "zero fact /
zero anchor loss" — tables and the 125 retained anchors are irreducible. The 50% figure was
relative to the +331 raw lines added; the substantive "brief" win (narrative → table/fault
tree, dropped rationale-storytelling) is delivered without dropping a single verified fact.

**Anchor audit:** full-file anchor set diffed HEAD vs working tree — 126 → 125, the only
delta being `rpcurl/.../RpcUrl.java:68` collapsed to the `:68` line-ref shorthand
immediately after `rpcurl/.../RpcUrl.java:64` in the same §15.1 sentence (the exact
convention §8.8 / §15.4 already use: `:485`/`:448`/`:454` after one `JwsVerify.java`
mention). Line 68 is preserved; no anchor information lost.

**Validation (r3):** mirror `diff -q` empty (PASS); `gradle :arch-test:test` →
`BUILD SUCCESSFUL`, 22 tasks up-to-date (PASS). Landed as a **new commit** on top of
e77ac0a (no amend/force-push — PR #24 already pushed).

## r4 — deeper condense (round 2: r3 was too conservative vs the ~50% contract)

Round 1 (r3) shrank §14–16 only ~20% and left §1–13 untouched — short of the maintainer's
"whole file briefer" ask. Round 2 hits it via the sanctioned **extraction lever** + a real
§1–13 redundancy pass. **Amended into `cc7c69b`** (verified `git status -sb` = ahead 1 of
origin, i.e. unpushed → amend, not a new commit).

**Lever: bulky non-day-1 reference material moved OUT of `SPEC.md`** into new files under
`skills/krpc/references/` (each a **verbatim slice** of the prior SPEC text, so every anchor
it carried survives byte-for-byte; SPEC keeps a one-line pointer + the day-1 quick-ref).
These files are NOT part of the SPEC mirror (skill-sync CI diffs only `SPEC.md` ↔
`skills/krpc/references/SPEC.md`, confirmed `.github/workflows/skill-sync.yml:17`):

| new reference file | lines | holds (was SPEC §) |
| --- | --- | --- |
| `native-image.md` | 118 | §13.1–13.5 (io.grpc/Quarkus-LTS matrix, `ext-rpc` support, io_uring eval, reflection coverage) |
| `operations.md` | 94 | §16.3–16.5 (observability, zero-trace fault tree, no-span, rollback) + §15.5–15.6 (`-rc` routes, smoke script) |
| `mcp-bridge.md` | 53 | §12.2 (schema derivation, methods, dispatch, verification transcripts) |

SPEC retains: §13 = pointer + day-1 checklist; §16 = §16.1 config-boundary + §16.2 port
quick-ref + pointer; §15.5 = one-line pointer; §12.2 = enable flag + summary + pointer.

**In-place compression** (fold post-table bullets into the row they explain; kill sentences
restating a table; rationale-storytelling → one-line parentheticals): §14 tables tightened;
§15.1/15.3/15.4 bullets folded into their tables; §12.3/12.4/12.6, §8.6/8.7, §1/§8-intro/§11/
§12.5 narrative compressed. No rule statement, DO/DON'T, code snippet, or config key altered.

**Per-section line counts (`awk` header→next-header, all three revisions):**

| region | e77ac0a (orig) | cc7c69b (r1) | r2 (this) | Δ vs orig |
| --- | --- | --- | --- | --- |
| §1–11 (authoring contract) | 371 | 371 | 356 | −4% (light redundancy pass) |
| §12 build/test/release | 238 | 238 | 176 | −26% (MCP extracted) |
| §13 native image | 129 | 129 | 17 | −87% (extracted) |
| §14 contract evolution | 60 | 49 | 42 | −30% |
| §15 consumer guide | 164 | 132 | 82 | −50% |
| §16 operations facts | 128 | 99 | 40 | −69% (extracted) |
| §17 checklist | 12 | 12 | 12 | 0 (day-1) |
| **§14–17 (primary target)** | **364** | 292 | **176** | **−52%** (target ~50% hit) |
| **whole file** | **1124** | 1052 | **747** | **−34%** (< 750 target) |

**Contract-wording safety:** §1–11 edits touched only rationale/narrative, never a rule
phrasing, DO/DON'T, code snippet, config key, or anchor — and the load-bearing *terms*
(`silently dropped`, `fail-closed`, `weak transaction`) were **kept verbatim** in SPEC, not
reworded. Grep of the krpc source tree + `docs/decisions` confirms the coupling is safe:
`grep "fail-closed"` → many hits in code comments/tests (`EnvUtils.java:46`,
`ServerContext.java:58,135`, `JwsVerify.java:72,91,328,374`, JWS hardening tests) but each uses
the word in its **own** sentence, none quotes a SPEC sentence; `grep "silently dropped"` →
`UnaryCallObserverIdempotencyTest.java:30` (unrelated observer context, its own wording);
`grep "weak transaction"` → no hits in code or `docs/decisions`. So no code/ADR cites the
compressed prose, and every retained term still matches the code's vocabulary.

**Anchor audit (SPEC.md ∪ the 3 reference files) vs cc7c69b SPEC:** 141 → 142 anchors/keys;
the two "missing" are non-losses — `RefUtils.java:179-196` is preserved as the `:179-196`
shorthand right after `RefUtils.java:173-198` (SPEC's own convention), and `RpcUrl.java:64` /
`:68` are subsumed by the range anchors `:53-54,63-64` / `:56-57,67-68` (line 64 ∈ 63-64,
68 ∈ 67-68) after folding the §15.1 bullets into the table. Zero anchor/fact loss.

**Validation (r4):** mirror `diff -q SPEC.md skills/krpc/references/SPEC.md` empty (PASS);
`gradle :arch-test:test` → `BUILD SUCCESSFUL`, 22 up-to-date (PASS); one broken in-file link
in `native-image.md` (`[§12](#…)` from the verbatim slice) repointed to `SPEC §12`.

## r5 — micro-fixes (codex `SPEC-BRIEF_REVIEW_codex_r1.md`, amended into e6d4301)

Two blocking qualifier/anchor losses from the r4 compression restored + one advisory:
1. **SPEC.md §12.3** — restored the deadline-authority "single point; a future Quarkus client
   binds the same authority so the two can't drift" note + the Batch-1
   `JwsVerify.bootstrapAndRegister` dedup anchor (dropped in r4 as storytelling; reviewer ruled
   it load-bearing).
2. **operations.md §16.4** — `no pinned grpc-java source` → `source/version`, restoring the dual
   caveat (neither grpc-java source **nor** version is pinned for the no-span inference).
3. **Advisory** — stripped the extra trailing blank line at EOF in `native-image.md`,
   `mcp-bridge.md`, `operations.md` (one final newline kept; `git diff --check` clean).

SPEC.md 747→749. Mirror re-synced (`cmp -s` PASS); `gradle :arch-test:test` green; verified
`git status -sb` ahead-1 (unpushed) before amending — no force-push.
