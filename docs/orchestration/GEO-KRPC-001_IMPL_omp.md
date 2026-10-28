# GEO-KRPC-001 — README answer-ification + FAQ + llms.txt (omp)

Branch `docs/geo-readme` off origin/dev. DOCS-ONLY: `README.md`, `README.zh-CN.md`,
`llms.txt`. Two local commits, no push, no AI signatures. SPEC untouched.

## What changed

Restructured the opening of both READMEs from a generic feature list into three
answer-first sections that directly answer GEO search intents ("智能体时代 服务端/微服务
开发", "agent-native RPC", "MCP gateway microservice", "contract-first agent backend"),
followed by an 8-question FAQ. All existing content below the opening is preserved and
unchanged. `llms.txt` (already present) refreshed with agent-native framing + homepage,
starter repo, and Central coordinates.

### Section map (README.md, old → new)

| old | new |
| --- | --- |
| L5–7 one-line definition + "handles RPC transport…" | **§ What is KRPC** — one-sentence quotable definition ("KRPC is a contract-first, agent-native RPC framework for the JVM…") + 2 supporting paras (interface = source of truth; leans on platform, ADR-0001; production use) |
| L9 "AI agent?" callout | moved into **§ Why the agent era needs it** as a closing pointer (skill + agent guide) |
| L11–18 "What It Does" bullets | facts folded into §What is / §Why / §How it differs (gRPC/HTTP2, JSON default, interfaces-as-truth, client gen, JDK21+VT, k8s/mesh) |
| L20 "does not own discovery/LB/…" | folded into §What is (ADR-0001 line) |
| L22 "used in production… names omitted" | folded into §What is ("e-commerce, education, and local-service products") |
| — (new) | **§ Why the agent era needs it** — shape change (agents call services as tools), interface = RPC contract AND opt-in MCP tool surface, ApiMeta + 3 HTTP surfaces (/agent/discover, /agent/invoke, /mcp), layered default-safe exposure |
| — (new) | **§ How it differs from protobuf-gRPC** — no .proto, JSON default, dual face, GraalVM native, honest gRPC interop |
| — (new) | **§ FAQ** — 8 Q&A |
| L24+ "Try It Live" onward | unchanged |

README.zh-CN.md mirrors this structure paragraph-for-paragraph (§KRPC 是什么 / §为什么
agent 时代需要它 / §与 protobuf-gRPC 的区别 / §FAQ), then original content from §模块 onward.

### llms.txt

- What-is blockquote rewritten to the agent-native framing (single contract → gRPC +
  JSON HTTP + typed clients + opt-in MCP), MCP-off-by-default + agentTool opt-in noted.
- Added: `Homepage: https://krpc.tech`, Central coordinates (`tech.krpc:rpc-api` etc.),
  Starter repo link (`github.com/martin1847/krpc-starter`), ADR-0004 MCP note, SPEC §12.2.
- 35 lines (< 40).

## Claim anchors (file:line — every capability claim traces to a repo fact)

| claim in README/FAQ | source of truth |
| --- | --- |
| MCP bridge `POST /mcp`, JSON-RPC 2.0 / Streamable HTTP, spec 2025-06-18, hand-written, no SDK, same netty host | `SPEC.md:433-442` (§12.2); `docs/decisions/ADR-0004-agent-friendly-introspection.md:71-77` |
| MCP bridge **default OFF** (`rpc.server.mcp.enabled=false`, env `KRPC_MCP`) | `SPEC.md:438,444-448`; `ADR-0004:94`; `docs/decisions/ADR-0005-architecture-gates.md:295-299` |
| tools = `@UnsafeWeb(agentTool=true)` subset only; `@UnsafeWeb` alone ≠ tool | `SPEC.md:438-439`; `docs/agent-guide.md:35-40`; `ADR-0004:43-45,90-93` |
| `agentTool` attribute **defaults false** | `rpc-api/src/main/java/tech/krpc/annotation/UnsafeWeb.java:31` (`boolean agentTool() default false;`) |
| method-level `@UnsafeWeb.AgentTool` | `rpc-api/.../UnsafeWeb.java:56-57` (`@Target(METHOD) @interface AgentTool{}`); `docs/orchestration/AGENT-002_IMPL_omp.md:101-113` |
| `GET /agent/discover` (web-only `ApiMeta`), `POST /agent/invoke` (same credential path as gRPC) | `docs/agent-guide.md:19-20,41-46`; `ADR-0004:9-13` |
| credential NOT bypassed on agent path | `SPEC.md:440-441`; `docs/agent-guide.md:41-46`; `ADR-0004:73-74` |
| dual face ports: gRPC `rpc.server.port` 50051, HTTP `http.port` 8080 (serves `/agent/*` + `/mcp`) | `docs/agent-guide.md:22-24`; `SPEC.md:437-438,478` |
| ApiMeta = signatures + DTO type trees + `@Doc` + constraints | `ADR-0004:9-13`; `docs/agent-guide.md:8-9,19` |
| no hand-written `.proto`; interface is contract (enforced at discovery) | `SPEC.md:14-16,29-34` (`RefUtils.java:117-120`); §14 `SPEC.md:596` (NS-1) |
| JSON default codec; wire = fixed `InputProto`/`OutputProto` envelope, not per-message protobuf | `SPEC.md:599-605` (§14) |
| gRPC path has no runtime schema handshake (`RPCURL-001`) → introspect over `/agent/discover` | `SPEC.md:648-652` |
| GraalVM native via `rpc-server-quarkus`; quickstart verified native | `SPEC.md:565` (§13); `docs/orchestration/AGENT-002_IMPL_omp.md:131-135`; `.github/workflows/native-smoke.yml:94-102` |
| Caffeine bounded-cache native-reflection pitfall | `SPEC.md:580-590` |
| `@UnsafeWeb` hides internal services (HIDDEN_SERVICE `-` prefix) | `SPEC.md:185-203` (`RefUtils.java:124-150`) |
| JDK 21 + virtual threads (supported runtime feature) | `ADR-0002`; AGENTS.md tooling section |
| typed clients TS/Dart/Python/Go(k6)/Java/rpcurl | README (unchanged clients list); satellite repos rpc-ts/rpc-dart-client/rpc-python (copy pack) |

## FAQ list (both languages)

1. Is KRPC compatible with existing gRPC / protobuf clients? (→ not directly; envelope)
2. Do I need `.proto` files? (→ no; interface is contract)
3. How do agents / MCP clients call a KRPC service? (→ KRPC_MCP + agentTool; /mcp; /agent/*)
4. What does the generated TypeScript / Dart client give me? (→ typed client from interface)
5. Does it run as a GraalVM native image? (→ yes, via rpc-server-quarkus)
6. How does it compare to Spring gRPC / protobuf-gRPC? (→ no IDL, JSON, agent/MCP faces)
7. What JDK and framework does it need? (→ JDK 21 + Gradle; Quarkus/Spring integrations)
8. How do I expose only some methods as agent tools? (→ layered opt-in, all-OFF defaults)

## Pack-vs-fact conflicts

None material. Notes where fact sharpened the pack copy:

- Pack framed krpc's MCP angle as "MCP gateway / 接口即工具". Fact: the MCP bridge is
  **off by default** and tools are an **opt-in per-method subset** (`agentTool`), never
  automatic. README states this explicitly rather than implying every service is a tool.
- Pack keyword list includes "agent OS / skills/hooks/human-in-the-loop era development".
  These are NOT krpc-code facts — omitted from the READMEs to avoid unfounded claims.
  Only "agent-native", "contract-first", "MCP", "typed clients", "GraalVM native" are
  used, each backed by an anchor above.
- "Typed clients for TS/Dart" is written as a capability; the generator + satellite
  client repos back it, but per-language type-fidelity was not independently re-verified
  in this repo (see NOT validated).

## NOT validated

- Did NOT build/run anything (DOCS-ONLY task); native-image and MCP claims rest on
  existing in-repo evidence (SPEC, ADRs, changelog, native-smoke.yml, transcripts under
  `docs/mcp-transcripts/`), not a fresh run this round.
- Did NOT verify the external starter repo `github.com/martin1847/krpc-starter` resolves
  publicly (URL taken from the goal brief; if still private the link 404s until it flips).
- Did NOT touch `docs-site/static/llms.txt` (a separate mirror file); task scope is the
  root `llms.txt` only. If the site is meant to serve the refreshed copy, that mirror
  needs a follow-up sync (out of scope here).
- Did NOT change SPEC, ADRs, or any code. FOR/NOT-FOR module boundaries untouched.

## Source-of-truth status

No ADR/roadmap/module status change needed — this is documentation surfacing existing
accepted capabilities (ADR-0001, ADR-0002, ADR-0004). No new capability introduced.
