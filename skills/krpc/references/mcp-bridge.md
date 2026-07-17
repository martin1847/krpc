# KRPC MCP bridge — full reference

Extracted from `SPEC.md §12.2` to keep the contract lean. Enable flag + summary stay in
`SPEC.md §12.2`; full behavior is here.

### 12.2 MCP bridge (agent tools over `POST /mcp`)

ADR-0004 P1: a hand-written [Model Context Protocol](https://modelcontextprotocol.io)
bridge (spec `2025-06-18`, JSON-RPC 2.0 over Streamable HTTP), on the same netty
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
- **Methods**: `initialize`, `notifications/initialized` (→ HTTP 202), `tools/list`,
  `tools/call`, `ping`. Transport is JSON-response mode only (one JSON object per
  POST); SSE is spec-optional and not used (krpc tools are unary). Per spec 2025-06-18:
  `GET /mcp` → `405 Method Not Allowed` (`Allow: POST`, no SSE stream offered here);
  an `MCP-Protocol-Version` header the server does not support → `400`
  (`initialize` is exempt — it negotiates via the body). Auth/rate-limit remain the
  gateway's responsibility, same as the P0 agent surface.
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
