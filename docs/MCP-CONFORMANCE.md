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
# MCP conformance: stateful 2025 and stateless 2026-07-28

Aegis serves MCP clients of both protocol eras on the same endpoint, and talks to existing MCP servers
of both eras downstream. This page maps the protocol requirements listed below to their Aegis status
and deterministic evidence. It is not an official or exhaustive conformance suite or certification.
The matrix was checked against the official
[2026-07-28 key changes](https://modelcontextprotocol.io/specification/2026-07-28/changelog).

Status words: **Implemented** (code plus a test), **Refused** (deliberately answered as
method-not-found or rejected, so nothing ungoverned passes), **Not implemented** (absent, and stated
here so no document claims it).

## How the two eras are told apart

| | Stateful (2025-03-26, 2025-06-18, 2025-11-25) | Stateless (2026-07-28) |
| --- | --- | --- |
| Version selected by | `initialize` | `params._meta["io.modelcontextprotocol/protocolVersion"]` on every request |
| Session | caller-bound `Mcp-Session-Id` (optional: `server.legacySessions: false` serves 2025 clients without one) | none; `Mcp-Session-Id` is ignored |
| Discovery | `initialize` result | `server/discover` |
| Routing headers | `Mcp-Method` / `Mcp-Name` checked when present | `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` required and must equal the body |
| Server-to-client input | refused | Multi Round-Trip Requests (`input_required`) |

Accepted revisions are configurable (`server.versions`, default all four; see
[CONFIGURATION.md](CONFIGURATION.md)). The engine is the same for both eras: a governed call is
evaluated identically whatever protocol carried it.

## Server side: what Aegis answers

| Requirement | Status | Enforced by |
| --- | --- | --- |
| 2025 `initialize` and `notifications/initialized` handshake | Implemented | `McpServerHandlerTest.legacySessionIsCallerBoundAndRequired`, `stdioConnectionServesBothEras` |
| 2025 session is bound to the authenticated caller; another principal gets 404; `DELETE` ends it | Implemented | `McpServerHandlerTest.legacySessionIsCallerBoundAndRequired` |
| A principal cannot exhaust the session registry (at most 32 sessions each) | Implemented | `McpServerHandlerTest.oneCallerCannotExhaustTheSessionRegistry` |
| 2025 stateless mode, assuming 2025-03-26 when the version header is absent | Implemented | `McpServerHandlerTest` (stateless handler), `ExistingMcpServerProxyTest.legacyStatelessServerWorksWithoutSession` (downstream) |
| 2025 `Mcp-Method` / `Mcp-Name`, when present, must agree with the body | Implemented | `McpServerHandlerTest.legacyRequestsWithMirroredHeadersMustAlsoAgreeWithTheBody` |
| 2026: no `initialize`; version and client capabilities in `_meta` on every request | Implemented | `McpServerHandlerTest.modernCallRequiresMatchingHeaders` |
| 2026: `server/discover` advertises versions, capabilities and identity | Implemented | `McpServerHandlerTest.discoverAndListArePrivateCacheableAndFilteredPerCaller`, `ContentGovernanceTest.capabilitiesAndMethodsExistOnlyWhenEnabled` |
| 2026: `Mcp-Method` and `Mcp-Name` required on POST and equal to the body; mismatch is 400 / -32020 `HeaderMismatch` | Implemented | `McpServerHandlerTest.modernCallRequiresMatchingHeaders` (including a header that names a harmless tool while the body calls another) |
| 2026: base64 sentinel form of `Mcp-Name` decoded before comparison | Implemented | `McpServerHandlerTest.base64SentinelNameIsDecodedBeforeComparison` |
| 2026: unsupported version is 400 / -32022 with the supported list | Implemented | `McpServerHandlerTest.unsupportedModernVersionListsSupportedVersions` |
| 2026: error codes renumbered (-32020 header mismatch, -32021 missing client capability, -32022 unsupported version) | Implemented | `JsonRpc` constants; header and version tests above |
| 2026: every result carries `resultType` (`complete` or `input_required`) | Implemented | `McpRoundTripTest`, handler tests |
| 2026: `serverInfo` in each result's `_meta` | Implemented | handler tests; visible in every transcript under `examples/adopters/*/results` |
| 2026: `ttlMs` and `cacheScope` on list and read results | Implemented | `McpServerHandlerTest.discoverAndListArePrivateCacheableAndFilteredPerCaller`. Lists are filtered per caller, so `cacheScope` is always `private`; resources are live data, so `resources/read` and resource lists carry `ttlMs: 0` |
| 2026: Multi Round-Trip Requests for `tools/call`, `resources/read`, `prompts/get`; the retry is governed again from step 1 | Implemented | `McpRoundTripTest.toolInputRequiredIsRelayedRedactedAndTheRetryIsGovernedAndForwarded`, `resourceReadRoundTrip`, `injectedAnswersAreScreenedAndMalformedRetriesRejected` |
| A 2025 client cannot carry `inputResponses`: explanatory error instead of an input request | Implemented | `McpRoundTripTest.legacyClientsGetAnExplanationInsteadOfAnInputRequest` |
| W3C `traceparent` in `_meta` (SEP-414) joins the audit record and is forwarded downstream | Implemented | `TracePropagationTest.toolCallTraceReachesTheExistingMcpServer`, `contentTraceReachesTheServerAndMetricsCountIt` |
| Origin validation (DNS-rebinding defence) | Implemented | `McpServerHandlerTest.transportLevelRejections` |
| Server-initiated requests (`sampling/createMessage`, `elicitation/create`, `roots/list`) towards the client | Refused | They would reach the model outside governance. MRTR is the governed replacement |
| `subscriptions/listen` (2026 change notifications) and 2025 `resources/subscribe` | Refused | method-not-found and not advertised; a client polls `tools/list` (cacheable per `ttlMs`) instead |
| `logging/setLevel` (removed in 2026) and per-request `io.modelcontextprotocol/logLevel` | Refused / ignored | Aegis emits no `notifications/message`; gateway logs go to its own log, decisions to the audit journal |
| `ping` | Implemented for 2025 only | removed in 2026, where it is method-not-found |
| Tasks extension (`io.modelcontextprotocol/tasks`) | Not implemented | not advertised; on [ROADMAP.md](ROADMAP.md) if an adopter needs long-running tool calls |
| `x-mcp-header` custom headers from tool parameters (SEP-2243) | Not implemented | Aegis requires only the standard headers; a custom header is neither required nor trusted |
| SSE resumability (`Last-Event-ID`) | Not implemented, as 2026 removes it | a broken response stream is retried by the client as a new request |

## Client side: talking to an existing MCP server

| Requirement | Status | Enforced by |
| --- | --- | --- |
| `era: AUTO` tries 2026 `server/discover`, then falls back to the 2025 handshake | Implemented | `ExistingMcpServerProxyTest.autoDetectsModernServerAndProxiesGovernedCalls`, `autoFallsBackToLegacySessionAndReestablishesExpiredSession` |
| 2025 session re-established after the server expires it | Implemented | `ExistingMcpServerProxyTest.autoFallsBackToLegacySessionAndReestablishesExpiredSession` |
| 2025 stateless servers (no session issued) | Implemented | `ExistingMcpServerProxyTest.legacyStatelessServerWorksWithoutSession` |
| 2026 requests send `Mcp-Method`, `Mcp-Name`, version, capabilities and `clientInfo` | Implemented | `ExistingMcpServerProxyTest.autoDetectsModernServerAndProxiesGovernedCalls` asserts the received headers |
| SSE responses parsed; server-initiated requests inside them refused | Implemented | `ExistingMcpServerProxyTest.sseResponsesAreParsedAndServerInitiatedRequestsAreRefused` |
| Responses bounded; redirects not followed | Implemented | `ExistingMcpServerProxyTest.oversizedDownstreamResponseIsBoundedAndRedirectsAreNotFollowed` |
| stdio servers in both eras; the child does not inherit gateway secrets | Implemented | `ExistingMcpServerProxyTest.stdioServersInBothErasAreGovernedAndDoNotInheritGatewaySecrets` |
| A changed upstream tool or prompt definition is quarantined | Implemented | `ExistingMcpServerProxyTest.rugPullOnDownstreamServerIsQuarantinedOnRefresh`, `ContentGovernanceTest.promptRugPullIsQuarantinedUntilAccepted` |

## Evidence class

These tests run against `TestMcpServer` and `TestStdioMcpServer`, independent protocol harnesses in
the `aegis-mcp` test jar that do not reuse the production handler. They provide deterministic evidence
for the listed requirements and deliberate refusals. They do not prove full specification conformance
or interoperability with every third-party MCP implementation.
Each adopter run under [examples/adopters](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/{{ site.github.source.branch }}/examples/adopters) uses real MCP 2026-07-28 requests over
Streamable HTTP through both front doors (Aegis's own server and Apache Knox); interoperability with a
named external MCP server is recorded only where such a run exists, in [VALIDATION.md](VALIDATION.md).
