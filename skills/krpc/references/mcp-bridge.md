# KRPC MCP bridge — full reference

Extracted from `SPEC.md §12.2` to keep the contract lean. Enable flag + summary stay in
`SPEC.md §12.2`; full behavior is here.

### 12.2 MCP bridge (agent tools over `POST /mcp`)

ADR-0004 P1: a hand-written [Model Context Protocol](https://modelcontextprotocol.io)
bridge (spec `2026-07-28`, JSON-RPC 2.0 over Streamable HTTP), on the same netty
HTTP host as `/agent/*` (`http.port`, default `8080`). No third-party MCP SDK; no
new module or Central artifact.

```properties
# Default OFF = byte-level zero new surface (the /mcp path is not even registered).
rpc.server.mcp.enabled=true
```
Env: `KRPC_MCP=true` (also honoured directly) or the SmallRye mapping
`RPC_SERVER_MCP_ENABLED`. Read by `rpc-server-quarkus` (`McpHandler`).

- **Tools = the `@UnsafeWeb(agentTool=true)` subset only.** `agentTool` (TYPE-level,
  default `false`) is a **deliberate subset of web exposure** — `@UnsafeWeb` alone
  does **not** create a tool. `/agent/discover` is unaffected (its web-filtered view
  is unchanged); the two surfaces are distinct. ON with no `agentTool` method = an
  empty `tools` list (valid).
- **Tool name** = `Service_method` (underscore-joined; matches the client-enforced
  `^[a-zA-Z0-9_-]+$`). `inputSchema`/`outputSchema` are JSON Schema derived from the
  DTO type tree + jakarta constraints (`@NotBlank`→`required`+`minLength`, `@Size`,
  `@Min`/`@Max`, `@Pattern`, `@Email`) + `@Doc`; `outputSchema` is the
  `RpcResult<T>`-unwrapped `T`.
- **`tools/call` runs the identical dispatch as `/agent/invoke`** (`WebInvoker.invokeWeb`):
  the credential check is **not bypassed**, and only agentTool methods resolve
  (unknown/non-agentTool/hidden → JSON-RPC `-32602`). Success → `content` text +
  `structuredContent` (unwrapped `data`); a non-zero `RpcResult.code` or a thrown
  credential/system error → `isError:true`.
- **Methods**: `server/discover`, `initialize`, `notifications/initialized` (→ HTTP 202),
  `tools/list`, `tools/call`, `ping`. Transport is JSON-response mode only (one JSON
  object per POST); SSE is spec-optional and not used (krpc tools are unary). Per spec:
  `GET /mcp` → `405 Method Not Allowed` (`Allow: POST`, no SSE stream offered here);
  an `MCP-Protocol-Version` header the server does not support → `400`
  (`initialize` is exempt — it negotiates via the body). Auth/rate-limit remain the
  gateway's responsibility, same as the P0 agent surface.

### MCP 2026-07-28 alignment

The bridge has been stateless since it shipped in 1.1.0 (no session id, one self-contained
JSON object per POST, no SSE), so the 07-28 stateless line is an **additive** alignment.

- **Dual version track.** `SUPPORTED_VERSIONS` = `2026-07-28`, `2025-11-25`, `2025-06-18`,
  `2025-03-26`, `2024-11-05`; `PROTOCOL_VERSION` (the `initialize` fallback) is `2026-07-28`.
  A 07-28 client sends **no `initialize`** — it states its version on every request in
  `_meta["io.modelcontextprotocol/protocolVersion"]`. krpc reads it at message level and,
  failing that, under `params`, and validates it exactly like the `MCP-Protocol-Version`
  header: present-and-unsupported → HTTP `400` + JSON-RPC `-32600`; absent → assume the
  default, never fail. `initialize` + `ping` stay for the older line through the 12-month
  deprecation window (do not delete them earlier).
- **`server/discover`** (MUST in 07-28) returns, in one cacheable response:
  `supportedVersions`, `capabilities` (`tools`), `serverInfo` (`name` = the exposed app name
  from `ApiMeta.app`, `version` = the real krpc build version), `instructions` — a short
  English paragraph telling the driving LLM that tools are `@UnsafeWeb(agentTool=true)` krpc
  methods named `Service_method`, that arguments are the JSON DTO described by `inputSchema`
  (single scalars wrapped as `{"value": …}`), and that a failure comes back as
  `isError:true` with an actionable `{code,message}` envelope — plus `ttlMs` `86400000` and
  `cacheScope` `public`. Like `initialize`, it is exempt from the version gate: it is the
  call that tells a client which versions exist.
- **L7 header consistency check.** `Mcp-Method` and `Mcp-Name` (case-insensitive) are
  optional on the wire here — absent is fine, old clients never send them — but a value that
  **disagrees** with the body is HTTP `400` + JSON-RPC `-32020` `"HeaderMismatch"`:
  `Mcp-Method` vs the JSON-RPC `method`, and `Mcp-Name` vs `params.name` on `tools/call`.
  A load balancer routing on the header while the server executes the body is a real
  split-brain attack surface, and krpc is the middleware sitting on that seam.
- **`tools/list`** carries `ttlMs` `86400000` + `cacheScope` `public` (the tool set is static
  per boot and identical for every caller — no per-credential variation) and is **sorted by
  tool name** (`McpSchema.toolDefs`), because reflection order is not stable across
  JVMs/builds and an unstable listing defeats client caching.
- **Design-exempt — deliberately NOT implemented** (do not "fix" without an ADR): SSE and its
  resumability (JSON-mode only, unary tools), sessions (`Mcp-Session-Id` never existed here),
  MRTR / `input_required` (the bridge never initiates a request toward the client), and
  `subscriptions` / `listen` (the tool set cannot change at runtime).
- **Verified** with real MCP clients over Streamable HTTP — `initialize` captured via a
  `@modelcontextprotocol/sdk` client script (the Inspector CLI does not print the raw
  result), `tools/list` + `tools/call` via the official `@modelcontextprotocol/inspector`
  CLI — on JVM **and** GraalVM native (Mandrel 25/JDK25); `initialize` +
  `tools/list` byte-identical across both, `tools/call` differs only in the runtime
  timestamp.
  Full verbatim transcripts (command lines + complete output):
  [`docs/mcp-transcripts/jvm.txt`](https://github.com/martin1847/krpc/blob/f7c8e70/docs/mcp-transcripts/jvm.txt)
  and [`docs/mcp-transcripts/native.txt`](https://github.com/martin1847/krpc/blob/f7c8e70/docs/mcp-transcripts/native.txt)
  (native includes the boot log). OFF path (`/mcp` absent, 404) is covered by
  `McpDisabledQuarkusTest`, not by the transcripts.
