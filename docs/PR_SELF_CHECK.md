# PR self-check — krpc

Walk this before opening a PR (humans and agents alike). Every item traces to a
real incident — no theoretical entries. Machine-checkable items live in
`.githooks/pre-push` (install: `git config core.hooksPath .githooks`; CI re-runs
the same script via the `selfcheck` workflow).

Hook-enforced (listed for awareness only):

- [ ] SPEC mirror: `SPEC.md` ≡ `skills/krpc/references/SPEC.md`, same commit. *(hook ①)*
- [ ] docs-site changes land on `dev`, never straight to `main`. A main-target
      integration is allowed only when its complete proposed-tip `docs-site`
      subtree exactly matches trusted local `refs/remotes/origin/dev`. Main-only
      additions, edits, renames, and deletions still block. *(hook ②)*
- [ ] SPEC stays under the line cap (`SPEC_MAX_LINES` in the hook); compress prose
      rather than raising it. *(hook ③)*

Gate ② correction (maintainer-approved, 2026-10-28): the blanket branch rejection
also blocked integrations of content already on `dev`, although they introduce
no unpublished site content. Content equality, not the source branch/PR name or
a caller override, is the exception. The check remains diff-scoped: main-target
ranges without site changes keep passing when the development ref is available.
Missing/non-commit development refs and invalid comparisons are errors, not
passes. Fetch `origin`'s `dev` into `refs/remotes/origin/dev` before a local
main-target check; the hook never fetches. Existing CI selfcheck explicitly
fetches that ref and checks checkout `HEAD` (including PR merge refs), so
base-only site differences in a merge result cannot hide behind a clean source
tip. Run `bash .githooks/pre-push-test.sh` for the isolated regression probes,
including the original main-only `url`/`baseUrl` deployment incident.

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
