# Active Context

Date: 2026-05-30

## Current Focus

- Initialize lightweight repository governance for KRPC.
- Make `docs/INDEX.md` the entry point for decisions, module boundaries, roadmap, and AI context.

## Open Questions

- Should ADR-0001 be accepted as-is, or should the inferred module boundaries be adjusted?
- Should test/demo modules remain in this repository long term?
- Should public adopter names ever be listed, and under what written approval process?

## Recent Decisions

- Created ADR-0001 as `proposed` because repository boundaries were inferred from README, Gradle modules, and existing `CLAUDE.md`.
- Accepted ADR-0002: KRPC uses JDK 21 as the Java baseline and treats virtual threads as a runtime feature.
- README uses anonymous production-use language because public adopter-name approval is not granted.
- Grouped core documentation into three module boundary files: API Contracts, Runtime Core, and Platform Integrations.
- Kept roadmap status separate from ADR status.
