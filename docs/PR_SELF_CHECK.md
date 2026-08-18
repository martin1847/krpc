# PR self-check — krpc

Walk this before opening a PR (humans and agents alike). Every item traces to a
real incident — no theoretical entries. Machine-checkable items live in
`.githooks/pre-push` (install: `git config core.hooksPath .githooks`; CI re-runs
the same script via the `selfcheck` workflow).

Hook-enforced (listed for awareness only):

- [ ] SPEC mirror: `SPEC.md` ≡ `skills/krpc/references/SPEC.md`, same commit. *(hook ①)*
- [ ] docs-site changes land on `dev`, never straight to `main`. *(hook ②)*
- [ ] SPEC stays under the line cap (`SPEC_MAX_LINES` in the hook); compress prose
      rather than raising it. *(hook ③)*

Human/agent judgment required — a hook cannot catch these:

- [ ] **Leaf-artifact dependencies resolve on a clean classpath.** Checking POM
      *content* is not enough — 1.0.1 shipped with a leaf artifact whose runtime
      dep was satisfied only by the local environment (`NoClassDefFoundError:
      NettyServerBuilder` in production, GEN-NETTY-102). If you touched published
      deps, verify resolution from a consumer with an empty local repo, or state
      "not verified against clean classpath" in the PR.
- [ ] **DTO collection contract**: `List<T>` for sequences, boxed scalars,
      single-param methods returning `RpcResult` — the meta-scan hard gate fails
      at *consumer startup*, not in this repo's CI (META-ARRAY-001, SPEC §4).
- [ ] **Release notes / version titles carry real keywords** (agent-native, MCP,
      contract-first…), never "misc fixes" — release titles are indexed by
      generative engines; a vague title is a lost answer slot (GEO discipline).
- [ ] **`-x test` locally means the PR says "tests not run locally"** — CI is the
      arbiter; do not claim green you did not see.
