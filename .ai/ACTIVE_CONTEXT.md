# Active Context

Date: 2026-05-30

## Current Focus

- Prepare a dependency bump plan without changing build files yet.
- Keep `docs/INDEX.md` as the entry point for decisions, modules, roadmap, plans, and AI context.

## Open Questions

- Should ADR-0001 be accepted as-is, or should the inferred module boundaries be adjusted?
- Should test/demo modules remain in this repository long term?
- Should public adopter names ever be listed, and under what written approval process?

## Recent Decisions

- Added `docs/plan/` for short-lived structured development plans that are smaller than roadmap items.
- Created ADR-0001 as `proposed` because repository boundaries were inferred from README, Gradle modules, and existing `CLAUDE.md`.
- Accepted ADR-0002: KRPC uses JDK 21 as the Java baseline and treats virtual threads as a runtime feature.
- README uses anonymous production-use language because public adopter-name approval is not granted.
- Grouped core documentation into three module boundary files: API Contracts, Runtime Core, and Platform Integrations.
- Kept roadmap status separate from ADR status.
