# Guardrails — shock-in-the-loop deterministic gates

**Shock-in-the-loop（电在回路）: soft prompts steer, hard gates hold the line.**
Prose (docs, checklists, agent instructions) can only steer behavior; what
actually holds the line is a deterministic gate wired into the loop — a hook
that exits 1, a CI job that goes red. Write prose only for judgment calls a
gate cannot make.

A mechanism for krpc service repos (and this repo itself). Kernel, in one loop:

```
real review finding / incident
  → entry in docs/PR_SELF_CHECK.md   (authors walk it before every PR)
  → machine-checkable entries sink into .githooks/pre-push
      (deterministic · pure git+grep · millisecond · diff-scoped — never builds/tests)
  → every check ships with a negative probe (prove it goes red, then revert)
  → next finding feeds the list
```

Rules:

- **Every hook check must cite a real incident or review finding. Never invent
  checks from theory.** New repos start with an empty list.
- The hook is a reminder (`--no-verify` bypasses it). CI re-running the same
  script with `--range` is the actual gate — wire both.
- Prose stays minimal (shock-in-the-loop): if a rule can be enforced by a hook,
  enforce it there and keep only a one-line pointer in the checklist. Longer
  prose is reserved for judgment calls a hook cannot make (e.g. "verify deps on
  a clean classpath").

Start from `pre-push.template` (generic driver, next to this file). Live
instances: this repo's `.githooks/pre-push` + `selfcheck` workflow;
[krpc-starter](https://github.com/martin1847/krpc-starter) ships the same wiring
for new projects.
