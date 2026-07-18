# OTEL-003 Final Review (Codex)

**Verdict: REQUEST-CHANGES**

Reviewed commit `dda602e` against its parent/merge base `62cf205`. The test assets are approvable;
the blocking findings are in the durable case file.

## Blocking findings

### HIGH — the case file omits the final field closure

`OTEL-003_IMPL_omp.md:565-636` stops at the `ext-rpc:1.0.3` archaeology and future fix
directions. `OTEL-003_IMPL_omp.md:725-781` stops with Envoy as a prime suspect and an unexecuted
discriminator. It does not record the final facts supplied in the review brief: `ext-rpc:1.0.5`
plus #32, infra's mesh-tracing dismantling, the 3/3 green result, and case status CLOSED.

Required change: add a final closure section and update the executive summary/root-cause ledger so
the durable file records the actual deployed fixes, their evidence, and the final verification
result. Preserve the earlier rounds as historical hypotheses, but mark each killed hypothesis as
killed rather than leaving an interim conclusion as the current truth.

### HIGH — Envoy and universal elimination are stated more strongly than the committed evidence

The banner at `OTEL-003_IMPL_omp.md:14-21` says "it's a wire-level (Envoy) injector", while the
round-7 body at `:756-781` only calls Envoy the prime suspect and proposes a discriminator. No Envoy
span match, before/after header capture, or mesh toggle result is recorded there.

Similarly, `:752-754` says one native probe "formally eliminates ALL in-process candidates" across
JVM/native, gRPC/HTTP, and ext-rpc. The recorded probe at `:733-750` exercises one native in-image
gRPC client/server path. The document itself says the literal ext-rpc assembly was not stood up at
`:719-721`; it records no corresponding native HTTP-face probe.

Required change: narrow the round-7 claim to what the probe establishes (wire equals the krpc CLIENT
span on the tested native gRPC path), then use the final infra-seat dismantling and 3/3 field evidence
to support the mesh root cause. Do not present the broader elimination as formally proved without
matching evidence.

### HIGH — the lab-only double-export mechanism is still labeled as the field cause

`OTEL-003_IMPL_omp.md:170-177`, `:329-335`, and `:428-434` attribute field finding #4 to duplicate
exporter/BSP registration. The same document records zero programmatic SDK/processor/exporter beans
in the consumer at `:259-260`, and later says the duplicate symptom disappeared after the
dependency/filter correction at `:642-644`, without an exporter-wiring change. The final root-cause
list in the review brief also excludes this mechanism.

Required change: retain duplicate registration as a useful reproduced failure mode and regression
guard, but classify it as killed for this field case and cite the kill evidence.

### MEDIUM — round-7 Envoy discriminator wording is not configuration-safe

At `OTEL-003_IMPL_omp.md:771-775`, disabling context propagation is grouped with disabling mesh
tracing and is expected to preserve the application's CLIENT parent. Istio's
`disableContextPropagation` removes trace-context headers; it does not preserve the original app
header. `disableSpanReporting` has different semantics and keeps propagation active. The exact
`peer.address=waypoint` / `component=proxy` fields at `:769-770` are also exporter/semantic-mapping
dependent, not guaranteed universal Envoy fingerprints.

Required change: describe the actual infra change that was performed and its before/after evidence.
Treat proxy attributes as observed values or examples, not a portable contract.

## Test-asset assessment

- **Storage pinning is fail-closed.** `examples/quickstart/build.gradle:51-54` pins
  `OpenTelemetryContextStorageProvider`, and
  `OtelClientChainQuarkusTest.java:58-64` asserts the live `LazyStorage` implementation is
  `QuarkusContextStorage`. If `opentelemetry-sdk-testing` returns and its testing provider wins, the
  active-class assertion turns red instead of silently reverting to ThreadLocal storage. A future
  OTel internal rename also fails loudly.
- **Wire/MDC parity bites.** `OtelClientMdcParityTest.java:141-196` checks no-SDK byte parity, exactly
  one `traceparent`, stale-MDC replacement, wire span id equal to the exported CLIENT span id, and
  correct CLIENT parentage. `OtelClientChainQuarkusTest.java:125-132` independently checks the parent
  extracted by the real downstream SERVER against the exported CLIENT span. A krpc injection-order
  regression would break these equalities.
- **The negative signature is real.** `OtelClientDoubleInjectionTest.java:87-99,156-172` adds a deeper
  writer and reproduces wire/exported-span divergence plus a ghost id. It is a characterization test;
  the positive parity tests are the absence gate.
- **No weakened tests found.** The existing Quarkus SERVER-span test only changes exporter type; its
  behavioral assertions remain. No assertion/test deletion, disable/ignore, filter, or soft-fail was
  introduced.
- **Coverage boundary (LOW).** The quickstart directly constructs `RpcClientFactory` and directly
  depends on `project(':rpc-client')`; it does not exercise ext-rpc's synthetic-client wiring or a
  published-POM dependency-resolution regression. That external boundary should be covered by
  ext-rpc/#32's own gate and named in the closure section.

## Diff and temporary-probe audit

- `dda602e^..dda602e`: 15 files, `+1891/-9`; findings doc, quickstart test configuration, and
  `src/test` only. `git diff --name-only dda602e^ dda602e -- '**/src/main/**'` is empty.
- No test weakening was found, and `git diff --check dda602e^ dda602e` is clean.
- The round-7 console exporter/native probe, temporary dependencies/configuration, native startup
  bean, and reachability metadata are absent from the committed tree. Only the findings narrative
  remains.
- Local `origin/dev` has advanced to `ba3d178` (JAPICMP-001), so the branch is ahead 1 / behind 1.
  Literal `git diff origin/dev --stat` consequently shows the six upstream JAPICMP files as deletions
  (`+1891/-582`); that is branch divergence, not part of `dda602e`. The commit/three-dot patch remains
  the 15-file test/findings change. Recheck the direct diff after integrating the current dev tip.

## Validation run

Executed from the worktree:

```bash
/opt/gradle/gradle/bin/gradle :rpc-client:test :examples:quickstart:test \
  --rerun-tasks --max-workers=2 --console=plain
```

Result: `BUILD SUCCESSFUL in 1m`; 32 actionable tasks, all 32 executed. Generated JUnit XML reports
zero failures/errors/skips for the touched OTEL suites, including the active-storage guard, MDC
parity, deeper-injector characterization, connected-chain tests, legacy-filter tests, and
separate-SDK test.

Also executed:

```bash
/opt/gradle/gradle/bin/gradle :examples:quickstart:dependencyInsight \
  --dependency opentelemetry-sdk-testing --configuration testRuntimeClasspath --console=plain
```

Result: no matching dependency on the quickstart test runtime classpath; build successful.

Not re-run: the reverted native one-off probe and external consumer/mesh 3/3 verification. Those
final field facts come from the review brief and must be backed by their actual issue/infra evidence
when the case file is corrected.

Architecture sources reviewed: ADR-0003 (W3C propagation), ADR-0006 (framework span creation), and
the completed OTEL-001 roadmap entry. No production module FOR/NOT FOR boundary or accepted
architecture direction is changed by this test-only commit; no ADR or roadmap status update is
needed for the branch itself.

## final closure

Re-reviewed amended commit `bcbf7cd` against the previously reviewed `dda602e`.

### Closed

- The new CASE CLOSED ledger at `OTEL-003_IMPL_omp.md:8-35` records the three field causes, deployed
  fixes, two trace anchors, and the 3/3 result. The two exhibits support the mesh archaeology,
  Telemetry-CR dismantling, post-dismantle parent-chain closure, 37-span/zero-ghost callback trace,
  `ext-rpc:1.0.5`, and legacy-filter deletion. GitHub PR #32 was independently verified as MERGED on
  2026-07-18 and as the direct `rpc-server-quarkus` → `rpc-client` dependency-alignment fix.
- The amended round-7 text at `OTEL-003_IMPL_omp.md:52-61,811-824` correctly limits the native probe
  to the exercised native in-image gRPC path. The broader in-process conclusion is now explicitly a
  joint conclusion from that probe plus the field dismantle-then-green evidence, and Envoy is
  attributed to the infra-seat archaeology rather than an in-repo capture.
- The two exhibits contain no internal hostname/FQDN, URL, IP, email, absolute path, personal user
  name, credential, token, password, key, cookie, authorization-header value, or private-key material.
  Their trace/span ids and short GitOps commit id are evidence identifiers, not credentials.
- `git diff dda602e bcbf7cd` contains exactly four Markdown changes: the findings doc, two exhibits,
  and this review doc. Test/source/build/config blobs are unchanged; no tests were weakened and the
  temporary round-7 console/native probe did not return. `git diff --check` is clean. Tests were not
  re-run because the amend has zero test/source/build delta; the prior 32-task forced run remains the
  applicable execution evidence.

### Still blocking

The added closure statements are correct, but the case file still contains unmarked, present-tense
historical conclusions that contradict the durable ledger and the claim that every killed hypothesis
is marked inline:

- `OTEL-003_IMPL_omp.md:63-73` still says the field requires a deeper CLIENT injector, names the Java
  agent as prime suspect, and recommends disabling agent gRPC/KRPC_OTEL, without a KILLED marker.
  `:197-198` and `:227-243` still identify consumer context visibility/drop as the defect/root cause;
  `:247-259` repeats that stale axis, and `:279-292` remains an unresolved STOP-AND-REPORT section.
  These must be marked as killed historical conclusions or rewritten to defer to CASE CLOSED.
- The double-BSP reclassification is incomplete. Kill evidence is present at `:210-216`, `:375-378`,
  and `:482-487`, but `:217-223` immediately reopens the field attribution as a consumer SDK/exporter
  bug and asks for a consumer issue; `:237-240` says the staging RED was compounded by double-export
  SDK wiring; `:375-384` repeats the consumer-wiring diagnosis. Keep the mechanism as harness-only
  characterization/regression coverage and remove or explicitly kill those field/consumer claims.

The document must have one current root-cause ledger. Historical rounds may retain their reasoning,
but superseded conclusions need an unmistakable KILLED/historical label at each contradictory summary
or must be rewritten so a linear reader cannot recover two incompatible current answers.

**REQUEST-CHANGES**

### label sweep final check (`be5dbb2`)

The previously cited contradictions are closed:

- The former `:217-223` double-BSP paragraph is now explicitly lab-only and KILLED for the field at
  current `OTEL-003_IMPL_omp.md:219-232`.
- The former `:237-240` round-1 root-cause conclusion is preceded by an unmistakable KILLED/historical
  qualifier at current `:236-241`.
- The former `:375-384` double-export section is now labeled KILLED and retained only as a lab failure
  mode at current `:398-407`.
- The round-6 banner, Javaagent subsection, STOP-AND-REPORT, context-visibility conclusion, and other
  previously cited interim blocks now carry local KILLED/historical qualifiers.
- `bcbf7cd..be5dbb2` changes only `OTEL-003_IMPL_omp.md` and this review Markdown file; there is no
  test/source/build/config delta, and `git diff --check` is clean. Tests were not re-run.

The all-document present-tense attribution sweep still finds two unclosed historical conclusions:

- `OTEL-003_IMPL_omp.md:88-97` and `:355-382` still present the leaking legacy filter as reproducing
  field ② exactly and as sufficient to explain the field symptoms, without a local KILLED/historical
  label. The verbatim filter later proved that leak was not the field artifact. Add a qualifier at the
  round-2 summary/section boundary stating that double-SERVER/re-parenting stands, but the leak→ghost
  field attribution was killed.
- `OTEL-003_IMPL_omp.md:409-415` still ends with “② remains OPEN”, and `:628-632` separately calls ②
  a “still-open defect”, again without a local KILLED/historical label on those status blocks. Both
  are superseded by the CASE CLOSED ledger and must be labeled historical/resolved where they occur.

The top-level statement that all earlier rounds are historical prevents these from overriding the
ledger for a careful reader, but it does not satisfy the stronger claim that **every** superseded
conclusion carries an unmistakable inline KILLED/historical label. The CASE CLOSED ledger is correct;
the label sweep is not yet complete.

**REQUEST-CHANGES**

## verdict: APPROVE (label sweep complete)

The enumerated round-6 banner, consumer-context, STOP-AND-REPORT, and double-BSP passages now carry unmistakable KILLED/historical labels or state a standing ledger conclusion.
