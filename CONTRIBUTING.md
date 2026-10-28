# Contributing to KRPC

Thanks for your interest in KRPC. This project is maintained by a single
maintainer, so small, focused, well-described contributions are the easiest to
review and merge.

## Where to Start

New here? Look for issues labelled
[`good first issue`](https://github.com/martin1847/krpc/labels/good%20first%20issue).
They are scoped to be approachable without deep framework knowledge. If none are
open, feel free to open an issue proposing what you would like to work on before
writing code — it avoids wasted effort on changes that fall outside the project's
[scope](docs/decisions/ADR-0001-repository-scope.md).

## Developer Certificate of Origin (DCO) — required, no CLA

KRPC uses the [Developer Certificate of Origin](https://developercertificate.org/)
(DCO), **not** a Contributor License Agreement. There is nothing to sign up for.
You certify authorship of your contribution by adding a `Signed-off-by` trailer to
every commit:

```
Signed-off-by: Your Name <your.email@example.com>
```

Add it automatically with:

```bash
git commit -s
```

The name and email must be real and must match your Git author identity. The DCO
is a lightweight, per-commit attestation; we deliberately do **not** require a CLA
(it is over-governance for a project this size).

## Pull Request Flow

1. **Branch from the latest base.** Create a feature branch — `feat/…`, `fix/…`,
   `chore/…`, `docs/…`:
   ```bash
   git switch -c feat/my-change
   ```
2. **Keep it focused.** One logical change per PR. Match the surrounding code
   style; do not reformat or refactor unrelated code in the same PR.
3. **Sign off every commit** (`git commit -s`, see DCO above).
4. **Open a PR** describing *what* changed and *why*. Link the issue it addresses.
5. **Squash on merge.** PRs are integrated with a squash merge to keep history
   linear — write your PR title/description so it makes a good single commit
   message.

Do **not** add AI/assistant signatures or co-author trailers to commits.

## Build and Test

JDK 21 is the baseline. Full build/test/release reference is in
[SPEC §12](SPEC.md#12-build-test-release). The common local commands:

```bash
gradle clean build -x :test-server-spring:test   # tests included; needs Docker (the
                              # DB-backed tests start their own MySQL via
                              # Testcontainers). The exclusion is a known-red Spring
                              # module — see the Repo Facts section of AGENTS.md
gradle clean build -x test    # only for environments without Docker
gradle allDeps                # dependency report
```

When you skip tests, say so in the PR and list what was not validated.

## Reporting Bugs and Requesting Features

Use the issue templates under **New issue**. For **security** vulnerabilities, do
**not** open a public issue — follow [SECURITY.md](SECURITY.md) instead.

## Project Conventions

Authoring conventions (the method contract, error model, DTO rules, validation,
auth) are documented in [SPEC.md](SPEC.md). Architecture and governance authority
lives in [AGENTS.md](AGENTS.md), the ADRs under `docs/decisions/`, and the module
docs under `docs/modules/`. Please read the relevant section before proposing a
change that touches public contracts or module boundaries.
