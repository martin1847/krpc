---
name: krpc
description: "Authoring and calling KRPC (tech.krpc) services. Use when writing or debugging a service that depends on tech.krpc / tech.krpc.ext, or when you see @RpcService, RpcResult<…>, @UnsafeWeb, discover→invoke over HTTP, or a Quarkus/GraalVM native build error from a krpc consumer. Covers the five rules that bite first (method signature, RpcResult, soft/hard errors, boxed DTOs, @UnsafeWeb), the HTTP agent discover→invoke path, and where native-image help lives. Do NOT use for non-krpc gRPC or plain JVM builds."
---

# krpc — authoring & calling krpc services (thin shim)

> **SoT is the krpc repo `SPEC.md`** — the tool-agnostic authoring handbook with
> `file:line` code evidence. This skill is a trigger + quick-reference only; when
> it and `SPEC.md` disagree, **`SPEC.md` wins**. Governance authority is `AGENTS.md`
> + ADRs (`docs/decisions/`).
>
> - Full handbook: **`references/SPEC.md` bundled next to this skill** (verbatim,
>   release-synced copy of the repo-root `SPEC.md` — CI fails if they diverge), so the
>   skill stays self-contained when copied into a consumer project. In the krpc repo
>   itself, the root `SPEC.md` is the canonical copy.
> - Agents discovering/calling a running service: read `docs/agent-guide.md`.
> - Calling a running service (auth lanes, URL shape, field formats, error codes,
>   `-rc` artifacts): `SPEC.md` §15. Evolving the `*-api` contract (japicmp gate,
>   version policy, deploy windows): `SPEC.md` §14. Production/ops facts (ports,
>   config build-vs-runtime, observability + zero-trace fault tree, release/rollback):
>   `SPEC.md` §16.
> - **Standing instruction — method names are contract, not guessable.** Until rpcurl
>   introspection lands (an external follow-up id **RPCURL-001**; not tracked in this repo's
>   roadmap/ADRs), **grep the `*-api` interface before calling** — a guessed method/field
>   resolves to `UNIMPLEMENTED`/`code:5`, not a hint
>   (`SPEC.md` §15.4). The HTTP `/agent/discover` surface does introspect (`SPEC.md` §12.2).
> - Native-image consumers: `SPEC.md` §13 + the `krpc-native-build` skill.

## Five rules that bite first (condensed from SPEC §1–§6)

Each line is a pointer, not the spec. Read the cited SPEC section before relying on it.

1. **Method signature — SPEC §1.** Every RPC method is `RpcResult<Dto> m(OneDto)`
   or `m()`: the return type MUST be `RpcResult<…>` and there is **at most one
   parameter**. Violate either and the method is **silently dropped** — not
   registered, no startup error, fails to resolve at call time. Merge multiple
   inputs into one DTO.
2. **RpcResult envelope — SPEC §2.** `code` `0`=OK / `>0`=business error
   (**no negative codes**); `msg` is non-null only when `code>0`; `data` is
   non-null only when `code==0`. Build with `RpcResult.ok(data)` /
   `RpcResult.error(code,msg)`; callers read `data` only after `isOk()`.
3. **Soft vs hard errors — SPEC §3.** Business failures **return**
   `RpcResult.error(code,msg)` — do **not** throw. System / security / validation /
   unexpected errors **throw** (the server wraps them in a gRPC `Status`, message
   **truncated to 100 chars**). Never use a Java exception to carry a business error.
4. **Boxed DTO fields — SPEC §4.** Scalar DTO fields MUST be boxed
   (`Integer`/`Long`/`Boolean`/`Float`/`Double`), never primitives: JSON
   serialization is `NON_NULL`, so a primitive serializes as `0`/`false` and
   destroys the "absent vs zero" distinction. Avoid `Map` and `enum` in response DTOs.
   `byte[]` is the one type passed bare on the wire (not JSON-boxed) — but TS/Dart
   clients cannot **send** `byte[]` as input.
5. **@UnsafeWeb — SPEC §6.** TYPE-level `@UnsafeWeb` marks a service reachable
   directly by browsers/frontend over the HTTP gateway; **without it** the service
   name is `-`-prefixed (hidden) and stays service-to-service only. Add it ONLY to
   frontend-facing services. `@UnsafeWeb(requireCredential=true)` (class) or
   `@UnsafeWeb.RequireCredential` (method) requires a JWT — you own the hardening.

## Agent call path — HTTP discover → invoke (P0, ADR-0004)

A running krpc server can be introspected and called over plain HTTP/1.1 JSON — no
gRPC stack. **These endpoints are on the plain-HTTP port (`http.port`, default
`8080`), which is separate from the gRPC gateway port (`rpc.server.port`, default
`50051`).**

```bash
# 1. Discover: web-only ApiMeta (only @UnsafeWeb services; hidden ones never appear)
curl http://HOST:8080/agent/discover

# 2. Invoke: app-relative Service/method + verbatim JSON input
curl -X POST http://HOST:8080/agent/invoke \
  -H 'Content-Type: application/json' \
  -d '{"service":"<Service>","method":"<method>","input":{ ... }}'
# success -> {"code":0,"data":{...}}   unknown/hidden -> {"code":5,...}
```

- `service`/`method` are **app-relative** (the `app/` prefix is stripped): call
  `quickstart/Hello/hello` as `{"service":"Hello","method":"hello"}`.
- Errors ride a `code` field in the JSON body (gRPC-style status); the HTTP status
  line stays `200` (transport emits only 200/404/500).
- **Credential is not bypassed relative to gRPC.** The `Authorization: Bearer <jwt>`
  header is forwarded into the same credential check as a normal call; a
  `requireCredential` service is checked no differently here. The agent path forwards
  **only** `Authorization` (plus client-id / `traceparent`) — **no `Cookie` header**,
  so the cookie credential fallback works on the web/gRPC path but **not on the agent
  surface** (current impl); send a bearer token. Actual **rejection** depends on auth
  being configured — the check enforces only when a verifier is registered
  (`rpc.server.jwks` set + loaded); missing/unloadable JWKS with `exitOnJwksError` off
  silently skips it (SPEC §8.7, `exitOnJwksError=true` in prod).
- **Auth and rate-limiting are the gateway's job**, not krpc core — put these two
  paths behind a gateway (ADR-0004).
- **`@UnsafeWeb(agentTool=true)` gates the MCP tool surface** (`POST /mcp`, below) —
  a deliberate subset of web exposure. `/agent/discover` + `/agent/invoke` are
  unaffected (still the full `@UnsafeWeb` set).
- Reachable in a **default consumer, JVM + native** (handlers carry `@Unremovable`;
  native reflection-config included). See `docs/agent-guide.md` for a real
  discover→invoke transcript.

## MCP bridge — `POST /mcp` (P1, ADR-0004, default OFF)

Same `8080` host also speaks MCP (spec `2025-06-18`, JSON-RPC 2.0 over Streamable
HTTP). Enable with `rpc.server.mcp.enabled=true` (env `KRPC_MCP=true`); default OFF =
zero new surface. Tools = the `@UnsafeWeb(agentTool=true)` subset only; tool name
`Service_method`; `input`/`outputSchema` derived from the DTO tree + jakarta
constraints + `@Doc` (`outputSchema` is the `RpcResult<T>`-unwrapped `T`).
`tools/call` runs the **same credential+filter dispatch as `/agent/invoke`** (not
bypassed). Methods: `initialize`, `notifications/initialized` (202), `tools/list`,
`tools/call`, `ping`; JSON-response mode, no SSE. Full contract: **SPEC §12.2**;
handshake transcript: `docs/agent-guide.md`.

## Live endpoint (self-verify)

A public sandbox at `https://demo.krpc.tech` runs the quickstart (`HelloService` only,
fake data, `agentTool=true`) with `/mcp`, `/agent/discover`, and gRPC-over-443 all live.
Agents can self-verify the discover→invoke and MCP examples above against it instead of
standing up a local server. Fake-data sandbox — may reset anytime; don't rely on state.

## Native image (GraalVM / Quarkus)

Building a krpc consumer as a native image? The single source of truth is
**`SPEC.md` §13** (version matrix, `ext-rpc` server support, build recipe,
reflection coverage, checklist). The `krpc-native-build` skill is the trigger +
fallback cheat-sheet for the same content.
