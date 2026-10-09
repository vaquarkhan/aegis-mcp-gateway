<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# aegis-mcp

MCP protocol layer for the 2025 and 2026-07-28 revisions: server handler, admin REST API, and
downstream HTTP/stdio MCP clients used by `type: mcp` sources. Depends on `aegis-engine` only; no
third-party runtime libraries.

## What it owns

- JSON-RPC / MCP method dispatch, origin checks, sessions (2025 era), header/body agreement
  (required on 2026-07-28, checked when the headers are present on 2025 requests).
- Admin routes for approvals, tool and prompt catalog accept, and audit verify.
- Bounded outbound HTTP helper (`BoundedHttp`), used by the downstream HTTP client and by outbound
  calls in other modules.
- Downstream MCP proxy clients (Streamable HTTP and stdio).

## What it does

This module turns MCP messages into calls on the governance engine and turns the engine's answers
back into MCP messages. It accepts requests over Streamable HTTP and over stdio, works out which
protocol revision the client speaks, and checks the JSON-RPC envelope before anything reaches the
engine. Tool requests go to `GovernanceEngine` and resource, prompt and completion requests go to
`ContentGovernor`; allow and deny decisions come from there, and this module only frames them. It
also serves the admin API used to decide approvals, accept quarantined definitions and verify the
audit journal. Finally, it provides the clients that let Aegis govern an existing MCP server without
changing that server.

## How it works end to end

```text
host -> McpServerHandler.handleHttp(req, caller)  |  StdioConnection.handleLine(line)
       HTTP only: Origin allow list (403), caller required (401, -32001), POST only
       Json.parse within ServerOptions.limits, then envelope checks
       _meta["io.modelcontextprotocol/protocolVersion"] present?
         yes: 2026-07-28, stateless, headers must match the body
         no:  2025 revisions, initialize first, optional caller-bound session
       dispatchModern / dispatchLegacy
         tools/list, tools/call                       -> GovernanceEngine
         resources/*, prompts/*, completion/complete  -> ContentGovernor (when offered)
         anything else                                -> -32601
  <- JSON-RPC result or error, with the HTTP status carried by the error
```

1. Transport. An HTTP host passes a `McpHttpRequest` (case-insensitive headers, body already bounded
   by the host) and the `CallerIdentity` it authenticated. A present `Origin` outside
   `ServerOptions.allowedOrigins` gets 403; a null caller gets 401 `-32001`. POST carries JSON-RPC;
   DELETE with `Mcp-Session-Id` ends the caller's own 2025 session (200, else 404); anything else
   gets 405 with `Allow: POST` (no GET SSE stream). For stdio, `handler.stdio(caller)` needs a
   non-null identity and returns a `StdioConnection` whose `handleLine` returns one line or null.
2. Parsing and limits. The body is parsed with `ServerOptions.limits()` (`JsonLimits.DEFAULT` when
   unset). It must be one JSON object with `jsonrpc: "2.0"`, a string `method` (client responses
   are refused, since the gateway sends no server-to-client requests), a string or integer id, and
   object `params`. A message without `id` is a notification. Over HTTP, a single `traceparent`
   header is copied into `_meta.traceparent` when `_meta` lacks one. On stdio the same checks
   produce error lines, and an invalid notification gets no reply.
3. Protocol revision. A request is modern when `params._meta` contains
   `io.modelcontextprotocol/protocolVersion`.
   - 2026-07-28 (stateless). The value must be a string (`-32602`). `MCP-Protocol-Version`,
     `Mcp-Method` and (for `tools/call`, `prompts/get` and `resources/read`) `Mcp-Name` must each
     appear once and equal the body after `HeaderValues.decode` (plain ASCII or `=?base64?...?=`),
     else 400 `-32020`. A version that is not 2026-07-28 or not enabled is 400 `-32022` with
     `data.supported` and `data.requested`. `Mcp-Session-Id` is not read; notifications get 202.
   - 2025 revisions (2025-11-25, 2025-06-18, 2025-03-26). `Mcp-Method` and `Mcp-Name`, when present,
     must still match the body (`-32020`); with no 2025 revision enabled the answer is `-32022`.
     `initialize` picks the requested version if enabled, else the newest enabled 2025 revision.
     With `legacySessions` on (the `ServerOptions.defaults()` setting), `SessionRegistry` returns a
     256-bit random, caller-bound id in `Mcp-Session-Id`, and every later message, notifications
     included, must send it; a sent `MCP-Protocol-Version` must equal the negotiated one. With
     `legacySessions` off, the header version is used (2025-03-26 when absent) and must be enabled.
   - On stdio there are no header checks and no sessions; `initialize` stores the version on the
     connection, and any other 2025 request before it gets `-32600`.
4. Method dispatch.
   - `server/discover` (2026-07-28 only) returns versions, capabilities, instructions, `ttlMs` and
     `cacheScope: "private"`. `initialize` and `ping` exist only in the 2025 path.
   - `tools/list` returns `engine.listTools(caller)`, on 2026-07-28 with `ttlMs` and `cacheScope`.
   - `resources/list`, `resources/templates/list`, `resources/read`, `prompts/list`, `prompts/get`
     and `completion/complete` are served only when a `ContentGovernor` offers that kind, and only
     then advertised (`subscribe` and `listChanged` false). A denial is error `-32010` with `code`,
     `step`, `reason` and `requestId` in `data`, sent with HTTP 200.
   - Everything else (such as `resources/subscribe`, `logging/setLevel`, or `ping` on 2026-07-28)
     is `-32601`, with HTTP 404. 2026-07-28 results also get `resultType: "complete"` unless set,
     and `_meta["io.modelcontextprotocol/serverInfo"]`.
5. `tools/call`. `name` must be a non-empty string and `arguments` an object (`-32602`). The handler
   builds a `ToolCall` with the name, arguments, request `_meta`, revision and multi round-trip
   (MRTR) fields, then calls `engine.call(caller, ...)`. `inputResponses` and `requestState` are read
   only on 2026-07-28; a 2025 request that sends them gets `-32602`. The result is
   `CallOutcome.toMcpResult()` from `aegis-engine`: the backend body when allowed, or an
   `isError: true` tool result with a text reason when denied (not a protocol error). Both carry
   `_meta["io.github.vaquarkhan.aegis/decision"]` with `outcome`, `requestId` and governance
   metadata, plus `code`, `step`, `reason` and data such as `approvalRequestId` on a denial. For 2025
   clients `resultType` is removed and an `input_required` result becomes an `isError` text asking
   for MCP 2026-07-28 (on `resources/read` or `prompts/get`, error `-32603`).
6. Admin API. `AdminApi.handle(req, caller)` checks Origin (403) and the caller (401), then routes
   on path segments. Approval routes delegate to `engine.approvals()` (`ApprovalService` checks
   approver groups, delegation and separation of duties); catalog, prompt and audit routes check
   groups in `AdminApi`. POST bodies are not read. See [Admin API](#admin-api).
7. Downstream MCP servers. `GatewayFactory` in `aegis-config` builds an `HttpMcpClient` or a
   `StdioMcpClient` for each `type: mcp` source (transport `http` or `stdio`), wraps it in
   `McpProxyToolSource` and, when content is configured, `McpContentSource`.
   - Era `AUTO` probes with a 2026-07-28 `server/discover`. A result listing 2026-07-28 (or, over
     HTTP, a 4xx with `-32020` or `-32021`) means modern; `-32022` fails (no shared version). Other
     200 or 4xx answers, or stdio errors and timeouts, fall back to `initialize` (default 2025-11-25)
     and `notifications/initialized`; other HTTP statuses fail. `MODERN` and `LEGACY` pin the era.
   - Modern requests carry `_meta` protocol version, client capabilities and client info, and the
     HTTP client mirrors `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name`. A 2025 HTTP session id
     is sent on every request and re-established once on 404.
   - HTTP: redirects are never followed, `BoundedHttp` enforces a total deadline and a byte bound
     (4 MiB default), JSON and SSE responses are accepted, a server-to-client request on SSE fails
     the call, notifications are skipped, pagination stops at 50 pages, and credential headers may
     not set protocol or transport headers such as `Host` or `Mcp-Session-Id`.
   - stdio: the child gets a small base OS environment plus the configured `env`, lines are bounded
     (an oversized line kills the child), server-to-client requests are answered `-32601`, stderr is
     drained and discarded, and a dead child is restarted on the next call.
   - `McpProxyToolSource` lists tools with discovery credentials, reports the endpoint as the egress
     target, and sends `tools/call` with `traceparent` in `_meta` and as a header. A backend
     `-32601` or `-32602` becomes an `isError` result; other failures become `ExecutionException`.

## Main types

Packages are relative to `io.github.vaquarkhan.aegis.mcp`.

| Type | Role |
| --- | --- |
| `server.McpServerHandler` | One handler for both eras and both transports; `handleHttp` and `stdio` |
| `server.McpServerHandler.StdioConnection` | Per-process stdio state; one line in, one line out |
| `server.ServerOptions` | Server name and version, enabled revisions, sessions, origins, TTL, instructions, JSON limits |
| `server.SessionRegistry` | Caller-bound 2025 sessions with idle TTL and a global cap; a caller's 33rd session evicts that caller's least recently used one |
| `protocol.JsonRpc` | JSON-RPC builders and error codes (`-32700` to `-32603`, `-32001`, `-32010`, `-32020` to `-32022`) |
| `protocol.McpVersions` | Revision constants, `_meta` keys and header names |
| `protocol.HeaderValues` | 2026-07-28 header value encoding and decoding |
| `http.McpHttpRequest` / `http.McpHttpResponse` | Host-neutral HTTP request and response records |
| `http.BoundedHttp` | Outbound send with a total deadline and a byte bound; 3xx returned unread |
| `admin.AdminApi` | Approvals, catalog, prompts and audit verify routes |
| `client.McpClient` | Downstream client interface and `McpCallException` |
| `client.HttpMcpClient` | Streamable HTTP client with `AUTO`, `MODERN` and `LEGACY` eras |
| `client.StdioMcpClient` | Subprocess client over newline-delimited JSON-RPC |
| `client.McpProxyToolSource` | `ToolSource` that exposes and proxies a downstream server's tools |
| `client.McpContentSource` | `ContentSource` for downstream resources, templates, prompts and completion |
| `client.ClientSupport` | Package-private helpers: modern `_meta`, bounded reads, result extraction |

## Admin API

Paths are relative to the admin root. Every route first needs an allowed or absent `Origin` (403)
and an authenticated caller (401). Unknown routes get 404.

| Method | Path | Who may call it | What it does |
| --- | --- | --- | --- |
| GET | `/approvals` | Approver groups | Lists pending approval requests |
| GET | `/approvals/{id}` | The requester or an approver | Returns one request; only the requester sees the token once approved |
| POST | `/approvals/{id}/approve` | Approver groups, non-delegated, not a requester | Approves; journaled before it takes effect; 409 if decided, 410 if expired |
| POST | `/approvals/{id}/deny` | Same as approve | Denies the request |
| GET | `/audit/verify` | Audit verifier groups | 200 `verified: true`, 409 `verified: false`, 503 when unavailable; 404 without a verifier |
| GET | `/catalog` | Approver groups | Name, source, class, status, reason and digest of each tool |
| POST | `/catalog/{tool}/accept` | Approver groups, non-delegated | Accepts a quarantined tool for the digest in `Aegis-Definition-Digest` |
| GET | `/prompts` | Approver groups | Name, source, status, reason and digest of each prompt (when prompts are offered) |
| POST | `/prompts/{prompt}/accept` | Approver groups, non-delegated | Accepts a quarantined prompt for the reviewed digest |

## Failure behaviour

- Oversized request (over the JSON limits): 413 `-32700`. Malformed JSON or a duplicate key: 400 `-32700`.
- Batch body, wrong `jsonrpc`, client response or bad id: 400 `-32600`. Non-object `params`: `-32602`.
- Unknown or not-offered method (refused, never proxied): `-32601` with HTTP 404.
- Unsupported revision: 400 `-32022` with the supported list. Header and body disagreement: 400 `-32020`.
- 2025 session missing: 400 `-32600`; unknown, expired or another caller's: 404; registry full: 503 `-32603`.
- Denied `tools/call`: `isError` tool result with the decision in `_meta`. Denied content: `-32010`, HTTP 200.
- Definition accept: 428 without exactly one `Aegis-Definition-Digest`; 409 when not quarantined,
  unknown, pinned or the digest changed; 403 for a delegated caller (refusal journaled best effort).
- Journal failure on accept: if the intent cannot be recorded the answer is 503 and the definition
  stays quarantined; a failure to journal the result record is ignored.
- `/audit/verify` I/O failure: 503 without file names. Any other admin exception: 500 naming only the class.
- Downstream: timeouts are flagged on `McpCallException`; an oversized body, a redirect status or a
  stdio child that exits fails the call.

## Tests

- `server.McpServerHandlerTest`: header agreement, base64 header values, unsupported revisions,
  caller-bound sessions and the per-caller cap, stateless 2025 mode, transport rejections, stdio in
  both eras.
- `server.ContentGovernanceTest`: content capabilities and methods exist only when enabled; reads,
  prompts and completion are governed; prompt quarantine; 2025 clients and the stdio front door.
- `server.McpRoundTripTest`: MRTR input requests are relayed and the retry is governed; 2025 clients
  get an explanation instead.
- `server.TracePropagationTest`: trace context reaches the downstream server for tools and content.
- `admin.AdminApiTest`: origin, authentication and group checks; approval lifecycle; digest-bound
  accept; journal failure blocks accept; audit verify scope; no route registers a backend.
- `client.ExistingMcpServerProxyTest`: era detection, session re-establishment, SSE handling,
  response bounds and no redirects, quarantine on a changed tool, stdio without gateway secrets.
- `http.BoundedHttpTest`: a backend that trickles its body hits the total deadline. Harnesses live in
  `testing` (`TestMcpServer`, `TestStdioMcpServer`, `TestEngines`).

## Developer instructions

```powershell
mvn -B -pl aegis-mcp -am test
```

1. Preserve protocol revision behavior; add tests for both eras when you touch framing.
2. Never follow redirects on outbound HTTP; keep response size and deadline bounds.
3. Admin routes stay group-scoped; CSRF Origin rules must remain fail-closed.
4. Document wire-visible deny or error code changes in [`docs/DENY-CODES.md`](../docs/DENY-CODES.md).

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-mcp)
- [MCP conformance](../docs/MCP-CONFORMANCE.md)
- [Developer guide](../docs/guide/developer.html)
- [AGENTS.md](../AGENTS.md)
