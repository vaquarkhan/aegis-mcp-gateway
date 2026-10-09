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
# Configuration reference

One JSON or YAML file (`.json`, `.yaml`, `.yml`). The parser is strict:

- An unknown key anywhere stops startup, with its path, for example
  `config.access: unknown key(s) [writeGrups]`.
- Secrets never appear in the file. Keys ending in `Env` name an environment variable, and
  startup fails if it is unset.
- Relative paths resolve against the directory of the config file.
- In YAML, quote protocol versions (`"2026-07-28"`), because unquoted they are dates and are rejected.

## Top level

| Key | Default | Meaning |
| --- | --- | --- |
| `gateway.name` | `aegis` | Name in audit, serverInfo and tokens |
| `sources` | required, >= 1 | Tool sources (below) |
| `policy` | required | At least one rule or `opa.url` (otherwise deny-all is rejected as a mistake) |
| `audit.file` | required | Hash-chained journal path |
| `state` | `memory` | Governance nonce, pending-approval, rate/budget-window, and signed-serial high-water state; see [durable local state](#state). TOFU pins use `catalog.pinFile`, not the state store |
| `serverCatalog` | - | **Off unless set.** Offline signed register of which `sources[]` may start; see [`serverCatalog`](#servercatalog) |
| `host`, `knox` | - | Host sections, parsed by that host only |
| `secrets` | `source: env` | Where every secret named in the file (`keyEnv`, `tokenEnv`, `passwordEnv`, `usernameEnv`, `valueEnv`, `hmacSecretEnv`, `keystorePasswordEnv`) is read from; see [secrets](#secrets) |

## `secrets`

One source for the whole gateway, selected by `secrets.source`. The names in the rest of the file stay
the same; only where their values come from changes. Lookups are strict: under `file` or `vault` a name
the provider does not know is "not set", even if the process environment has it.

| `source` | `settings` | Behaviour |
| --- | --- | --- |
| `env` (default) | none | The process environment |
| `file` | `directory` | One file per name in a mounted directory (Kubernetes Secret volume, `/run/secrets`, CSI driver). One trailing newline is removed; an empty file is not set; files over 64 KiB are refused. Names are letters, digits, `_`, `.` and `-`, never starting with a dot, and the resolved real path must stay inside the directory (Kubernetes `..data` symlinks still work) |
| `vault` | `address`, `path`, `mount` (`secret`), `tokenEnv` or `tokenFile` (exactly one), `namespace`, `caFile`, `timeoutMs` (5000) | One KV version 2 secret read at start-up from `GET <address>/v1/<mount>/data/<path>` (HashiCorp Vault and OpenBao). Its keys answer the names. The address must be on `egress.allowHosts` (and its range on `allowCidrs` when set); redirects are not followed. The Vault token comes from the process environment or a file, never from the provider. HTTP 403, 404, other errors and unreachable servers stop the gateway from starting; the token is never echoed. Rotation takes effect on the next start |
| any other name | provider-defined | A `SecretProviderFactory` on the classpath (`META-INF/services/io.github.vaquarkhan.aegis.config.spi.SecretProviderFactory`). It receives the egress check and must use it for every network destination |

The own host parses `host:` with the same lookup, so `host.identity.static.tokens[].tokenEnv` and
`host.tls.keystorePasswordEnv` resolve from the selected source too.

## `server` (MCP endpoint)

| Key | Default | Meaning |
| --- | --- | --- |
| `versions` | all four | Accepted MCP revisions: `2026-07-28`, `2025-11-25`, `2025-06-18`, `2025-03-26` |
| `legacySessions` | `true` | Issue and require a caller-bound `Mcp-Session-Id` for 2025 clients |
| `allowedOrigins` | `[]` | Browser Origins accepted; any other present Origin gets 403 |
| `toolsListTtlMs` | `0` | `ttlMs` on 2026 list/discover results (`cacheScope` is always `private`) |
| `instructions` | - | Server instructions |
| `maxRequestBytes` | 1 MiB | Inbound JSON bound |
| `sessionIdleSeconds` / `maxSessions` | 1800 / 10000 | Legacy session limits |
| `name` / `version` | gateway name / build | serverInfo |

## `access`, `approval`, `vrp`

| Key | Default | Meaning |
| --- | --- | --- |
| `access.writeGroups` | `[]` | May call MUTATE (0.1 "write unlock"); others get `READONLY_CALLER` |
| `access.destructiveGroups` | = writeGroups | May call DESTRUCTIVE |
| `access.approverGroups` | `[]` | May decide approvals and accept quarantined tools |
| `access.auditVerifierGroups` | `[]` | May call the fixed-journal `GET /audit/verify` endpoint. This role is independent of approvers; an empty set disables verification for every caller. |
| `approval.keyEnv` / `approval.keyFile` | - | HMAC key, at least 32 bytes. `keyEnv` wins when both are set. Without a key or approver groups, approval-required calls are denied |
| `approval.requiredFor` | `[MUTATE, DESTRUCTIVE]` | Classes needing per-call approval. READ stops startup. An explicit empty list turns per-call approval off for every class, so use it only in tests |
| `approval.requestTtlSeconds` / `tokenTtlSeconds` | 1800 / 600 | Lifetimes |
| `approval.advertiseControlArguments` | `true` | Show `approvalToken` / `dryRun` / `dryRunReceipt` as optional properties on write tools |
| `vrp.enabled` / `vrp.ttlSeconds` | `true` / 900 | DESTRUCTIVE needs a dry-run receipt |

Clients pass control values in `params._meta` under `io.github.vaquarkhan.aegis/approvalToken`,
`.../dryRun` and `.../dryRunReceipt`. The 0.1 argument names are also accepted, but only when the
upstream schema does not declare a property of that name. They are always removed before the call
reaches the backend.

What an approval and a receipt are bound to: the gateway name (`gateway.name`), the tool,
its class, the source, the typed resources, the argument digest and the digest of the pinned tool
definition. A token issued by another gateway that shares the key, or before the tool definition
changed, is refused. Give each gateway its own `gateway.name` (and ideally its own key). Tokens issued by a
build older than this binding are refused after an upgrade.

Separation of duties: the requester can never approve its own request, compared after Unicode NFKC
normalisation and case folding, so `Olga` cannot approve for `olga`. A caller with a delegation chain
(`act` claims) can never approve at all. An approval request shows the approver the redacted
arguments (the same `output` redaction rules as tool results, capped at 8 KiB) and, after a dry run, the redacted
preview. That evidence is stored in the state store with the request until it expires, so treat the
state store like the audit journal. Every decision is journaled as `APPROVAL_DECISION` before it takes
effect; if the journal cannot record it, the decision is not made and the caller gets 503.

## `sources[]`

Common keys:

| Key | Default | Meaning |
| --- | --- | --- |
| `id` | required | `[a-z0-9][a-z0-9_-]{0,63}`, unique |
| `type` | required | `mcp`, `http`, or a plugin type |
| `namePrefix` | `""` | Prefix on exposed names (avoids collisions) |
| `timeoutMs` | 30000 | Backend budget per call |
| `defaultClass` | `DESTRUCTIVE` | Class of discovered tools you did not list (mcp, plugin) |
| `unlistedTools` | `EXPOSE` | `EXPOSE` or `HIDE`; `HIDE` exposes only listed tools. Any other value stops startup |
| `credentials` | `none` | `{type: bearer, tokenEnv}`, `{type: basic, usernameEnv, passwordEnv}`, `{type: header, header, valueEnv}`, or `{type: token-exchange, ...}` for per-caller tokens; see [Per-caller credentials](#per-caller-credentials-rfc-8693-token-exchange) |
| `trust.state` | `ACTIVE` | `ACTIVE` exposes the source through normal catalog integrity. `QUARANTINED` contributes no callable tool. |
| `trust.evidence` | - | Required when `trust.state: QUARANTINED`; an operator-maintained reference explaining why the source is withheld. |

Per-tool governance keys (in `tools` for mcp and plugin sources, where the map key is the upstream
tool name, and inline for http tools):

| Key | Meaning |
| --- | --- |
| `class` | `READ`, `MUTATE`, `DESTRUCTIVE` (required for http tools) |
| `resources[]` | `{type, pointer}` or `{type, pointers: [...], join: "."}`, plus `required` (default true) |
| `dryRun` | `{mode: GATEWAY}` (default), `{mode: ARGUMENT, argument: validateOnly}`, `{mode: TOOL, previewTool: <tool>}` |
| `allowedGroups` | Only these groups see or call the tool |
| `actionScopes` | OAuth scopes the caller's verified token must carry to call the tool, for example `[reports:read]`. Matching is exact and case-sensitive (RFC 6749 section 3.3); every listed scope is required and none implies another. Checked at step 2 after schema validation, action formation, and resource extraction, but before resource-scope rules, policy, approval, credentials, or the backend; a missing scope is `SCOPE_DENIED` with `requiredActionScopes` and `missingActionScopes`. On the own host a JWT caller also gets HTTP 403 with `WWW-Authenticate: Bearer error="insufficient_scope", scope="<route scopes> <tool scopes>"`, so an MCP client can request a token that carries them (step-up); the body is still the JSON-RPC result. The required scopes are part of the approval and dry-run binding, so changing them voids earlier approvals for that tool. Only the own host's JWT identity establishes scopes: static tokens, SPIFFE, stdio and Knox identities carry none, so a tool with `actionScopes` is denied to them (fail-closed). Values must be RFC 6749 scope tokens: printable ASCII without space, `"` or `\`. These are per-tool requirements; `host.routes.mcpScopes` stays the separate route-level check |
| `timeoutMs` | Overrides the source timeout |

### Per-caller credentials (RFC 8693 token exchange)

With `credentials.type: token-exchange`, each call carries a token issued to the calling user for this
backend. The gateway sends the caller's verified inbound JWT to the identity provider's token endpoint
with its own client credentials (OAuth 2.0 Token Exchange, RFC 8693) and presents the returned token to
the backend. The inbound token itself is never sent to a backend.

| Key | Default | Meaning |
| --- | --- | --- |
| `tokenEndpoint` | required | The identity provider's token endpoint. It must pass `egress`, checked at startup |
| `clientIdEnv`, `clientSecretEnv` | required | The gateway's own confidential client (sent as HTTP Basic, RFC 6749 section 2.3.1) |
| `audience` and/or `resource` | one is required | What the issued token is for; an exchange without either is refused at startup |
| `scope` | none | Optional scope to request |
| `timeoutMs` | 5000 | Bound on one exchange (100 to 60000) |
| `checkSubject` | `true` | An inspectable three-part issued JWT must carry a non-blank string `sub` equal to the caller's subject; a compact encrypted JWE is refused because its subject cannot be checked. Opaque tokens are passed on as issued |
| `discovery` | `{type: none}` | Static credential (`bearer`, `basic`, `header`) used for `tools/list`, which has no caller |

Fail-closed: no verified caller JWT on the request, a refusal from the token endpoint, a reply without
`access_token`, an unexpected token type or a subject mismatch denies the call with `BACKEND_ERROR`
(step 10); nothing falls back to a static credential. Issued tokens are cached per inbound token (by
its SHA-256) until 30 seconds before `expires_in`, at most one hour. Tokens never appear in the audit
journal, hook requests or policy input.

Where it applies: the own host with `host.identity.type: jwt`. Static tokens, SPIFFE, stdio and the
Knox front door bind no exchangeable token, so a `token-exchange` source refuses every call there. The
`http`, `mcp` and `a2a` sources send the credential; JDBC, ZooKeeper and Spark Connect use their own
settings. Evidence: [Keycloak 26.8.0 run](evidence/TOKEN-EXCHANGE-KEYCLOAK.md).

```yaml
credentials:
  type: token-exchange
  tokenEndpoint: https://idp.example.com/realms/acme/protocol/openid-connect/token
  clientIdEnv: AEGIS_TX_CLIENT_ID
  clientSecretEnv: AEGIS_TX_CLIENT_SECRET
  audience: orders-api
  discovery: {type: bearer, tokenEnv: ORDERS_DISCOVERY_TOKEN}
```

### `type: mcp`

| Key | Meaning |
| --- | --- |
| `transport` | `http` (default) or `stdio` |
| `url` | Streamable HTTP endpoint (http) |
| `command`, `env`, `envFrom`, `workingDir` | Subprocess (stdio). The child receives a minimal environment plus `env`, and `envFrom` values read from the gateway's environment |
| `era` | `AUTO` (default), `MODERN`, `LEGACY` |
| `legacyVersion` | 2025 revision to request (default `2025-11-25`) |
| `maxResponseBytes` | Downstream bound (default 4 MiB) |
| `resources` | Expose the server's resources and resource templates (absent = OFF). `{uriPatterns: [globs] (required, "*" refused), allowedGroups: [], allowBinary: false}` |
| `prompts` | Expose the server's prompts (absent = OFF). `{names: [globs] (empty = all), allowedGroups: [], allowBinary: false}` |
| `completions` | `false`. Forward `completion/complete` for this source's prompts and resource templates; needs `resources` or `prompts` |

Resources and prompts are governed as READ actions (see ARCHITECTURE.md section 7): a read is routed
to the one source whose `uriPatterns` match (overlapping patterns are refused, never guessed),
exposed prompt names get the source's `namePrefix`, prompt definitions are pinned like tool
definitions (pin key `prompt:<name>`, so `catalog.pins` and the pin file cover them). With a signed
`serverCatalog`, the prompt pin must also belong to the source that advertised it; a same-named pin
from another source cannot authorize it. `allowBinary: false` refuses `blob` contents and image/audio prompt parts, which redaction cannot
inspect. Policy rules see the method as the tool name (`resources/read`, `prompts/get`,
`completion/complete`) and the typed resource `mcp:resource:<uri>` or `mcp:prompt:<name>`, so
`policy.rules[].tools` and `.resources` globs work unchanged:

```yaml
policy:
  rules:
    - {id: docs, groups: [analysts], tools: ["resources/read"], resources: ["mcp:resource:file:///data/public/*"]}
    - {id: prompts, groups: [analysts], tools: ["prompts/get", "completion/complete"]}
```

### `type: http`

`baseUrl` plus `tools[]`, each with `name`, `description`, `class`, optional `title`,
`inputSchema` (default `{type: object}`), `outputSchema`, and:

| Key | Meaning |
| --- | --- |
| `request.method` | GET, POST, PUT, PATCH, DELETE |
| `request.path` | Template. `{arg}` becomes one percent-encoded segment and must be in `inputSchema.required`; values `.` and `..` are refused |
| `request.path` | `{arg/}` is a **path-typed** placeholder: several segments, for APIs whose identifier is itself a path (`/webhdfs/v1/{path/}`, `/znodes/v1/{path/}`). Each segment is validated and encoded separately, so `/user/hive/warehouse` is accepted while `/user/../etc`, `/a/./b`, `/a//b` and any `%` are refused. Leading and trailing slashes are normalised, so `"/"` addresses the root. Use `{arg}` unless the API really needs a path |
| `request.query` | Map of literal values or exactly `{arg}`. Absent optional args are omitted. Order is preserved |
| `request.body` | JSON template. A string that is exactly `{arg}` is replaced by the typed value; `{*}` is the whole argument object |
| `request.headers` | Static headers (`Authorization` is not allowed here; use `credentials`) |
| `response.pointer` | Optional RFC-6901 JSON pointer selecting the part of a JSON response returned to the model |
| `response.status.pointer` / `response.status.equals` | Optional exact integral backend-success assertion, evaluated on the raw JSON response before `response.pointer` projection. A missing, non-integral, malformed or unequal value is a fail-closed backend error even when HTTP is 200. |
| `response.maxBytes` | Response bound (default 1 MiB) |

`response.pointer` is a JSON Pointer, not an expression language: it names a path and has no functions,
variables or pipes, so a response filter cannot read the gateway's environment.

#### A draft from an OpenAPI document

`java -jar aegis-gateway-all.jar --openapi-draft openapi.yaml draft.yaml [--source-id id] [--base-url url]`
writes a reviewable configuration with one `http` tool per JSON operation of an OpenAPI 3.x document.
Every tool is `DESTRUCTIVE` and the only policy rule denies everyone, so nothing is callable until a
person classifies the tools, adds resources and dry-run modes, and writes policy. Local `$ref`s are
inlined; remote ones are not fetched and become a warning. Header and cookie parameters, non-JSON
bodies and names Aegis cannot template are listed as warnings at the top of the file. The command never
overwrites a file and never contacts the API. A run on the Apache Iceberg 1.10.1 REST catalog document
is `OpenApiDraftIcebergTest`.

### Plugin types

`type: <factory type>` plus `settings: {...}`, which the factory reads strictly. Bundled plugins:

| Type | Jar | Settings |
| --- | --- | --- |
| `kafka-admin` | `examples/aegis-examples` (+ `kafka-clients`) | `bootstrapServers`, `clientPropertiesFile`, `requestTimeoutMs` |
| `spark-connect` | `examples/aegis-spark-connect` (+ the Spark Connect JVM client) | `host`, `port` (15002), `useSsl` (false), `tokenEnv` (needs `useSsl: true`), `userId` (aegis-gateway), `maxRows` (100), `threads` (8) |
| `a2a` | `aegis-source-a2a` (no other dependencies) | `cardUrl` (required), `endpointUrl` (pins the JSON-RPC URL), `toolName` (source id with `_`), `maxTextChars` (16000), `maxResponseBytes` (1 MiB), `cardTimeoutMs` (5000); see [adopters/configurations/a2a](../adopters/configurations/a2a/README.md) |

The `a2a` source offers one A2A 1.0 agent as one tool with arguments `text` and optional `data`, sent as
one JSON-RPC `SendMessage`. An A2A message cannot select a skill, so classify the tool by the strongest
thing the agent can do. The endpoint the card names must be on the card's origin or equal
`endpointUrl`, and it is part of the tool description, so a moved endpoint quarantines the tool. Card
signatures are not verified; A2A 0.3 cards and non-JSON-RPC bindings are refused; tasks are not
continued across calls. The agent's host must be listed in `egress.allowHosts`.

Input schemas from any source may use local `$ref`s (`#`, `#/$defs/...`, `#/definitions/...`), which are
resolved and enforced. A `$ref` to anything else is never fetched: the call is denied with `INVALID_INPUT`.

Plugin backends that are not HTTP still pass the egress guard: the Spark Connect server is checked
as `http(s)://host:port` (gRPC runs over HTTP/2), so its host must be allowed like any other.

## `policy`, `scopes`

- `policy.rules[]`: `{id, effect: ALLOW|DENY, groups | actors, tools (globs), classes, resources (globs)}`.
  Any matching DENY wins. Otherwise the first matching ALLOW allows. Otherwise the call is denied.
  `groups: ["*"]` means any authenticated caller.
- `policy.opa`: `{url, timeoutMs: 2000, includeArguments: false, tokenEnv}`. Only
  `{"result": true}` or `{"result": {"allow": true}}` allows. The URL must be an absolute http(s) URL
  without user-info and must pass `egress` at startup, like the remote hook and OTLP endpoints; an OPA
  sidecar on loopback therefore needs `egress.allowCidrs: [127.0.0.1/32]` (and `http` in `schemes` if no
  source already adds it).
- `scopes[]`: `{groups, resources}`. When present, every resource of a call must be covered by a
  scope of one of the caller's groups.

## Controls

| Key | Default | Meaning |
| --- | --- | --- |
| `egress.allowHosts` | `[]` | Extra hosts (`*.example.com` means subdomains only). Source hosts are added automatically: an `http` source's `baseUrl`, an `mcp` source's `url`, and configured plugin endpoints (each `kafka-admin` bootstrap server as `tcp://host:port`, the `spark-connect` server). Every call checks those configured hosts and every DNS answer. Kafka brokers advertised after bootstrap are a separate topology hop that must be constrained by Kafka listener configuration and network policy |
| `egress.allowCidrs` | `[]` | Private, loopback and link-local answers are denied unless covered here |
| `egress.schemes` | `[https]` + source schemes | Allowed schemes |
| `egress.checkArgumentUrls` | `true` | http(s) URLs anywhere in the arguments must pass too |
| `rateLimits` | off | `perCallerPerMinute`, `perCallerPerToolPerMinute`, `budgetPerHour`, `costs: {READ: 1, MUTATE: 5, DESTRUCTIVE: 20}` |
| `breaker` | 5 / 30 s | `failureThreshold`, `openSeconds` |
| `screening.mode` | `ENFORCE` | `ENFORCE`, `AUDIT`, `OFF` for arguments |
| `screening.toolDefinitions` | `ENFORCE` | The same modes, for tool descriptions (quarantine) |
| `screening.content` | `ENFORCE` | The same modes, for resource and prompt content returned to the model, and prompt definitions |
| `screening.phrases` / `decodeBase64` | built-in / true | Phrase list; also screen base64-decoded strings. `decodeBase64` applies to argument screening; definition and content screening always decode |
| `output.maxBytes` / `redact` / `useDefaultRedactions` | 1 MiB / [] / true | Output bound and regex redactions |
| `output.allowBinary` | false | Pass binary parts of tool results (`data` of image and audio content, any `blob`). They are opaque to redaction and screening, so by default such a result is withheld and the call is denied with `POLICY_DENIED` at step 10. When allowed, binary parts are passed unchanged and text parts are still redacted. Resources and prompts use their own `allowBinary` keys |
| `hooks[]` | `[]` | Extension controls; see [hooks](#hooks-extension-controls) below |
| `audit.fsync` / `audit.includeArguments` | true / false | Durability; audit stores the argument digest only unless enabled |
| `audit.checkpoint.file` | - | **Off unless set.** Head export: after every `everyRecords` records, every `everySeconds` when new records exist, at open and at close, the gateway appends `{ts, journal, seq, head, reason}` to this file and forces it to storage. Put it on storage the journal writer cannot rewrite (another volume, a WORM or object-lock mount, a log shipper's spool). It must not be the journal; one checkpoint file per journal |
| `audit.checkpoint.everyRecords` / `everySeconds` | 100 / 60 | Export triggers. `everySeconds: 0` disables the timer. On open the gateway refuses a journal that holds fewer records than the last exported head, or differs at it (truncated, replaced or rewritten). A failed export is retried before the next record; if the retry fails the record is refused and the call is denied with `AUDIT_UNAVAILABLE`. Check offline with `--verify-audit <journal> --checkpoints <file>` |
| `GET /audit/verify` | disabled unless `access.auditVerifierGroups` is configured | Fixed configured-journal verification; success is only `{"verified":true}`, a broken chain is only `{"verified":false}`. No caller-supplied path, record count, hash, journal content or verifier diagnostic is exposed. |
| `catalog.mode` | `TOFU` (`REQUIRE_PINS` with `serverCatalog`) | `REQUIRE_PINS` quarantines unpinned tools. With `serverCatalog` any other value is a configuration error |
| `catalog.pins` / `pinFile` | - | Operator pins (`tool: sha256:...`); persisted TOFU pins. With `serverCatalog`, `pinFile` is refused and an inline pin that the signed catalog does not carry, or that disagrees with it, is a configuration error |
| `catalog.manifest` | - | Ed25519-signed tool pins. Use `--manifest-create ... --serial <n>` to make a lower or later-unnumbered manifest fail against the state store high-water mark |
| `catalog.manifest.file` / `signatureFile` / `publicKeyFile` | - / `<file>.sig` / - | Signed pins: `{"version": 1, "gateway": "<optional gateway.name>", "pins": {...}}` with a base64 Ed25519 signature over the file's exact bytes and an X.509 PEM public key. The signature is verified before anything in the file is read; a manifest for another `gateway.name`, any edit, or another key stops the gateway from starting. Signed pins are authoritative like `pins` (no run-time `accept` overrides them); an inline pin that disagrees with the manifest is a configuration error. Use with `mode: REQUIRE_PINS` so unsigned tools stay quarantined. A manifest created with `--manifest-create ... --serial <n>` is rollback-protected: once it was accepted, a manifest with a lower serial or none is refused (the record lives in the state store, see `serverCatalog`). An unnumbered manifest is judged by its signature alone; retire a key (new key pair, new `publicKeyFile`) when such a manifest must never be accepted again. The manifest pins tools only; prompt definitions keep their own pins and acceptance. Workflow: `--manifest-keygen`, `--manifest-create` from a reviewed `GET /aegis/v1/catalog` reply, `--manifest-sign` (keep the private key off the gateway host), `--manifest-verify` |
| `catalog.refreshSeconds` | 0 | Periodic re-discovery |
| `catalog.acceptNewToolsAfterStart` / `failOnSourceError` | false / true | Late tools; startup behaviour when a source is down |
| `state.*` | `memory` | Governance state provider; see [`state`](#state) below |
| `metrics.enabled` | `true` | Decision counters and latency histogram (Prometheus text). The gateway's own server serves them on `host.metrics.path`, Knox on `aegis-mcp/v1/metrics` |
| `tracing.otlp.endpoint` | - | **Off unless set.** OTLP/HTTP traces endpoint, for example `http://collector:4318/v1/traces`. Checked against `egress` at startup |
| `tracing.otlp.serviceName` | `gateway.name` | `service.name` resource attribute |
| `tracing.otlp.tokenEnv` / `headers` | - | Bearer token from the environment, and any static headers the collector needs |
| `tracing.otlp.timeoutMs` | 5000 | Per-batch deadline, including reading the response |
| `tracing.otlp.maxQueue` / `batchSize` / `flushMs` | 2048 / 128 / 2000 | Span queue bound, most spans per POST, and the exporter thread's idle poll interval. A span is sent as soon as it arrives, together with up to `batchSize - 1` already queued; the exporter does not wait for a batch to fill |

Trace context needs no configuration: a W3C `traceparent` in `params._meta` (or, as a fallback, the
HTTP `traceparent` header) is continued, recorded in audit (`traceId`, `spanId`, `parentSpanId`)
and forwarded to HTTP backends and downstream MCP servers. Without one, a new trace is started.

Setting `tracing.otlp.endpoint` additionally **exports** one span per governed request, so the gateway
appears in your existing tracing backend as a hop between the agent and the engine:

```yaml
tracing:
  otlp:
    endpoint: http://otel-collector:4318/v1/traces
    serviceName: aegis-kafka
    tokenEnv: OTLP_TOKEN        # optional
```

Three properties of the exporter worth knowing before you enable it:

- **It cannot slow a request down.** The request thread only offers the span to a bounded queue; a
  background daemon thread batches and posts. When the queue is full, spans are **dropped and counted**,
  because losing telemetry is better than adding latency to a governed call. A collector that is broken,
  slow or absent changes nothing about enforcement.
- **No caller identity, arguments or results are exported.** Span attributes are `aegis.request_id`,
  `aegis.kind`, `aegis.outcome`, `aegis.step`, `aegis.tool`, `aegis.tool_class`, `aegis.source`,
  `aegis.reached_backend` and `mcp.method.name`. Join a span to the audit journal on `aegis.request_id`
  when you need who and what, which keeps the tracing backend out of scope for PII review.
- **Wire format caveat.** The OTLP specification says trace and span ids are hex-encoded in OTLP/JSON,
  deliberately departing from the protobuf JSON mapping that would base64-encode them, and several
  OpenTelemetry SDKs emitted base64 for years. Aegis emits hex, per the specification. This is verified
  against a local HTTP test endpoint that asserts the document shape, **not** against a live collector, so confirm
  compatibility in your own environment rather than treating it as tested.

There is no OpenTelemetry SDK dependency: the engine stays dependency-free so it can be dropped onto a
host classpath. If you want the SDK, write your own `DecisionListener` instead.

## `serverCatalog`

An offline, Ed25519-signed register of the MCP servers (configured `sources[]`) this gateway may run,
read once at startup. With it in force, adding a source, changing where a source points or what it runs, or adding or
changing a tool stops the gateway unless the review key signed that exact configuration.

**Host trust anchor.** The host that launches the gateway can pin the review key outside this file
with `--require-server-catalog /etc/aegis/review.pub`, the environment variable
`AEGIS_REQUIRE_SERVER_CATALOG_KEY` or the system property `aegis.requireServerCatalogKey` (an
absolute path; it is read from the process environment, never from `secrets.source`). The gateway
reads the anchored key once, compares the configured key to that immutable value, and verifies the
catalog with the anchored value. It then refuses a configuration without `serverCatalog`, or one
whose `publicKeyFile` is a different key, so editing the configuration cannot drop or swap the catalog. Without the anchor the review
key is named by this file, and the catalog guards source blocks against people and tools that
cannot change the `serverCatalog` block and its key. It is not a runtime registry:
records only refer to sources already declared in this file by `id`. Nothing in it registers a URL, a
command or a credential, and no admin route reads, replaces or bypasses it.

| Key | Default | Meaning |
| --- | --- | --- |
| `serverCatalog.file` / `signatureFile` / `publicKeyFile` | - / `<file>.sig` / - | The signed catalog, its base64 signature over the file's exact bytes, and the review public key (X.509 PEM). The signature is verified before anything in the file is read |
| `serverCatalog.breakGlass.file` / `signatureFile` / `publicKeyFile` | - | Optional containment order, signed with a different key (see below) |

Catalog format:

```json
{"version": 1, "serial": 3, "kind": "aegis-server-catalog", "gateway": "<optional gateway.name>",
 "servers": {
   "kafka":  {"state": "ACTIVE", "sourceDigest": "sha256:<hex>",
              "pins": {"list_topics": "sha256:<hex>", "prompt:triage": "sha256:<hex>"}},
   "legacy": {"state": "REVOKED", "evidence": "INC-42: backend compromised"}}}
```

What the gateway enforces at startup:

- Every configured source must have a record; an unlisted source stops the gateway.
- A source whose record is `QUARANTINED` or `REVOKED` is not parsed, constructed, discovered or added
  to the egress allow list. Only its `id` is read. Those records need `evidence` and may not carry
  `sourceDigest` or `pins`.
- An `ACTIVE` source's configuration block must hash to `sourceDigest`: the canonical SHA-256 of the
  block as written, where key order and the spelling of a number (`1.0` or `1`) do not matter; a
  quoted `"8080"` and the number `8080` still differ. Any edit (URL, command,
  credentials reference, tool settings, trust state) stops the gateway and prints the new digest.
- The record's `pins` is the source's exact allow list. A tool the record does not pin is never bound,
  so no inline pin, tool manifest or other server's pin can vouch for it. A name may be pinned by only
  one server. Prompt pins use the `prompt:<name>` key and are source-bound too: a prompt activates
  only when that key appears in the record for the source that advertised it.
- Every inline `catalog.pins` entry and every `catalog.manifest` pin must also be a pin of an
  `ACTIVE` record, with the same digest; any other pin stops the gateway. This is what keeps prompt
  pins signed-only as well.
- `catalog.mode` must be `REQUIRE_PINS` and `catalog.pinFile` must be unset. Run-time acceptance
  (`POST /aegis/v1/catalog/{tool}/accept` and the prompt equivalent) is refused, so nothing outside
  the signed file can re-trust a changed definition.
- A source the config itself marks `trust.state: QUARANTINED` stays quarantined. The catalog can only
  keep or tighten what the configuration says.

**Break-glass containment.** During an incident, a responder holding the break-glass key can stop
named servers without re-signing the reviewed catalog:

```json
{"version": 1, "kind": "aegis-break-glass", "gateway": "<optional gateway.name>",
 "incident": "INC-9", "notBefore": "2026-10-05T12:00:00Z", "notAfter": "2026-10-05T18:00:00Z",
 "servers": {"kafka": "QUARANTINED"}}
```

An order can only take capability away. It cannot activate a server, add a pin, skip an approval or
grant a scope. The gateway refuses a `breakGlass.publicKeyFile` that is the review key, and an order that does not
verify against the break-glass key (so one signed with the review key), names a server the
catalog does not list, asks for `ACTIVE`, has a window longer than 24 hours, or is outside its window
at startup. An expired order is a startup error, not a silent re-activation: remove it or issue a new
one. The window is checked only at startup; if it ends while the gateway runs, the containment stays
until the next restart.

**Rollback protection.** The catalog, a break-glass order and a `catalog.manifest` can each carry a
signed `"serial"`, a whole number from 1 that the signer raises with every new version (the create
commands take `--serial <n>`). The gateway keeps the highest serial it accepted for each kind in its
state store with one atomic high-water update; a candidate that arrives after a higher serial is
refused. `memory`, `derby-local` and `postgres` provide that capability. A third-party state provider
keeps working without an SPI change, but a numbered signed file is refused until that provider opts
in to `HighWaterStateStore`, so rollback protection never quietly becomes a non-atomic read/write
sequence. With `derby-local` or `postgres` state the record survives restarts (with `postgres` every
replica shares it); with the default in-memory state it lasts one process. An unnumbered file is judged by its signature
alone, so retire a key when an old unnumbered file must never be accepted again.

Changes take effect on restart only. There is no hot reload, no runtime registration and no
break-glass elevation path.

**Workflow** (all offline, from the gateway jar):

1. Start once with `catalog.mode: REQUIRE_PINS` and no `serverCatalog`, then save a reviewed
   `GET /aegis/v1/catalog` reply (and a `GET /aegis/v1/prompts` reply if the gateway offers prompts).
2. `--manifest-keygen review.key review.pub`, then
   `--server-catalog-create aegis.yaml catalog.json servers.json [prompts.json]`. This writes ACTIVE
   records with each source's `sourceDigest`, its tools' pins and, from the prompts reply, its
   `prompt:<name>` pins. Every listed entry is pinned whatever its status, so remove anything you
   did not review. Review the file, then `--manifest-sign servers.json review.key`
   and `--server-catalog-verify servers.json review.pub <gateway.name>`.
3. For incidents, use a separate key: `--manifest-keygen incident.key incident.pub`, then
   `--break-glass-create order.json <gateway.name> <incident> <minutes> <id>=QUARANTINED|REVOKED ...`,
   `--manifest-sign order.json incident.key` and `--break-glass-verify order.json incident.pub <gateway.name>`.

Keep both private keys off the gateway host.

## `state`

Governance state is what must outlive one request: single-use approval and dry-run receipt nonces,
pending approval requests, rate and budget windows, and the highest accepted `serial` of each signed
file kind (rollback protection, see `serverCatalog`). TOFU tool and prompt pins are owned by
`CatalogIntegrity` and optionally persisted through `catalog.pinFile`; they are not stored by
`StateStore`. Governance state is keyed by caller identity and action, not by MCP session, because the
2026-07-28 revision has no protocol sessions.

| Key | Default | Meaning |
| --- | --- | --- |
| `state.type` | `memory` | `memory` keeps state in the process, for one replica. `derby-local` keeps it in an embedded Apache Derby database, for one durable process. `postgres` uses a shared PostgreSQL database for approval/receipt, rate and budget state. The runnable `aegis-gateway` jar includes both durable providers; the Knox `knox-bundle` jar includes `postgres` only, so add the `aegis-state-derby` jar and Apache Derby beside it in Knox `ext/` to use `derby-local` there. Any other value must match a `StateStoreFactory` found by ServiceLoader, or startup fails |
| `state.maxEntries` | 1,000,000 | Entry bound for `memory` only |
| `state.settings.path` | - | Required for `derby-local`. Resolved against the config directory and refused if it escapes that directory |
| `state.settings.jdbcUrl` / `usernameEnv` / `passwordEnv` / `namespace` | - | Required for `postgres`. Credentials are environment variable names; `namespace` is the explicit sharing boundary. |
| `state.settings.connectTimeoutSeconds` | 10 | Optional PostgreSQL connect timeout, 1 to 120 seconds |

```yaml
state:
  type: derby-local
  settings:
    path: state/aegis
```

`derby-local` survives a restart of the same process; `DerbyStateStoreTest` covers restart, expiry,
concurrent single-use nonces and refused rate costs. It is not shared state. Derby's embedded mode
lets only one JVM open a database for writing, so a second Aegis process on the same path fails to
start rather than split approval state.
`DerbyStateStoreTest.aSecondProcessCannotOpenTheSameDatabaseWhileTheFirstHoldsIt` proves this with a real
second JVM.

`postgres` uses short PostgreSQL transactions, unique keys, conditional upserts and `DELETE ... RETURNING`
for shared single-use approval/receipt nonces and fixed rate/budget windows. Gateways share that state only
when `jdbcUrl` and `namespace` match. It does not make TOFU catalog pins or circuit breakers shared. Use
`sslmode=verify-full` and a dedicated least-privilege database role in production. The real
`PostgresStateStoreIT` and `TwoGatewayProcessesPostgresIT` integration profile should pass against the
target database before you rely on it; see [POSTGRES-STATE.md](POSTGRES-STATE.md).

An embedding host can pass its own store through `BuildContext`, in which case the `state` section is
accepted but not used. The Knox extension does not do this today, so under Knox the `state` section
applies as written.

## `hooks`: extension controls

A hook is where a deployment adds its own control (a PII detector, a data-classification lookup, an
internal policy service) without forking the engine. Hooks run in configured order at step 8, after
every built-in check and before approval, so denial order and audit output stay deterministic.

Every hook governs **all four** governed operations by default, not only tool calls: `tools/call`,
`resources/read`, `prompts/get` and `completion/complete` all put data or instructions into the
model's context.

Listings (`tools/list`, `resources/list`, `resources/templates/list`, `prompts/list`) do **not** run
hooks. They are filtered by exposure, name and URI patterns, redacted and content-screened, and they
are not audited, like `tools/list` in 0.1. If you need a hook to decide what a caller may see, apply
it to the read itself: hiding an item from a listing does not stop a caller asking for it by name.

| Key | Default | Meaning |
| --- | --- | --- |
| `type` | required | Built in: `remote` and `sql-readonly` (below). Optional jar: `project-governance`. Anything else must match a `GovernanceHookFactory` found by ServiceLoader; a factory that claims a built-in name is refused |
| `mode` | `ENFORCE` | `ENFORCE` denies, `AUDIT` records only, `OFF` never runs the hook |
| `operations` | all four | Any of `TOOL_CALL`, `RESOURCE_READ`, `PROMPT_GET`, `COMPLETION` |
| `methods` | any | Globs over the tool name (`create_*`) or the MCP method (`resources/*`) |
| `groups` | any caller | Only run for callers in one of these groups |
| `sources` | any source | Only run for requests routed to one of these source ids |
| `settings` | `{}` | Passed to the hook implementation |

`AUDIT` is a real dry run, not a partial rollout: the hook still runs and what it would have done is
written to the audit record as `hookAudit`, but it cannot deny and it cannot change the response.
Start a new control in `AUDIT`, read the journal, then switch it to `ENFORCE`.

In `ENFORCE`, a hook that throws, returns nothing or times out denies the request (or withholds the
output, in the after phase). It never fails open. A hook may only deny with `POLICY_DENIED`,
`INVALID_INPUT` or `PROMPT_INJECTION`; any other code becomes `POLICY_DENIED`, so a local control
cannot invent wire codes that clients have never seen.

### `type: remote`: a control in another process

The built-in `remote` hook is the no-Java extension path. Aegis POSTs a description of the operation
to an HTTP endpoint and the endpoint answers `{"allow": true}` or
`{"allow": false, "code": "...", "reason": "..."}`. That is how a control written in Python or Go
joins the chain.

| Setting | Default | Meaning |
| --- | --- | --- |
| `url` | required | Endpoint. Checked against `egress` **at startup**; startup fails if it is not allowed |
| `name` | `remote` | Name used in denial reasons and audit |
| `timeoutMs` | 2000 | Total deadline, including reading the body |
| `tokenEnv` | - | Environment variable holding a bearer token presented to the endpoint |
| `phases` | `[BEFORE]` | `BEFORE` (can deny), `AFTER` (can withhold the output or content) |
| `includeArguments` | `false` | Send the request arguments |
| `includeResult` | `false` | Send the result, in the after phase |
| `maxResponseBytes` | 8192 | Response bound, clamped to 256 bytes through 64 KiB |

Arguments and results stay inside the gateway unless you opt in, because the endpoint is a third
party from the data's point of view. Without them the endpoint still sees the caller, the operation,
the typed resources and the argument digest, which is enough for identity-based and resource-based
controls. Anything other than a 200 with a literal boolean `allow` is a denial.

```yaml
hooks:
  - type: remote
    mode: AUDIT                 # watch it for a week before enforcing
    operations: [RESOURCE_READ, PROMPT_GET]
    sources: [docs]
    settings:
      url: https://dlp.internal.example.com/aegis
      tokenEnv: DLP_TOKEN
      phases: [BEFORE, AFTER]
      includeResult: true       # this endpoint has to see the content to classify it
```

Three reference endpoints written against this contract (a PII guard, a secrets guard and a change
window), with tests and a live end-to-end run against the gateway jar, are in
[adopters/hooks](../adopters/hooks/README.md).

### `type: sql-readonly`: keep a statement-forwarding tool accurately classified READ

A tool that forwards free text to a SQL endpoint cannot be classified from its configuration:
`POST /sql` with `select 1` is a read, and the same tool with `drop table orders` is not. A `READ` tool
needs no write group, no approval and no dry-run receipt, so without a guard that tool carries no
control at all. This hook is why the `run_sql_readonly` tools in the shipped adapter configs may stay
`READ`.

| Setting | Default | Meaning |
| --- | --- | --- |
| `arguments` | required | Argument names holding a statement, for example `[sql]`. No default on purpose |
| `name` | `sql-readonly` | Name used in denial reasons and audit |
| `maxChars` | 8192 | Statement length bound; zero or a negative value means 8192 |

```yaml
hooks:
  - type: sql-readonly
    methods: [hive_run_sql_readonly]     # bind it to the statement-carrying tools
    settings: {arguments: [sql]}
```

Comments and quoted text are removed first, then the statement must be a single statement, must start
with `SELECT`, `WITH`, `SHOW`, `EXPLAIN`, `DESCRIBE`, `DESC`, `VALUES` or `TABLE`, and must contain no
writing or session-changing keyword anywhere. An unterminated quote or comment is refused rather than
guessed at. A call that carries none of the named arguments is **denied**, because a guard that checks
nothing reads like protection.

Two limits apply:

- This is a keyword and shape guard, not a SQL parser, and it is dialect-agnostic. A statement that
  passes is not *proven* read-only: `select my_function()` passes, and in most engines a user-defined
  function can write.
- The control that actually enforces read-only is a **read-only credential** on the source. Aegis
  resolves backend credentials per source, so give a SQL source a role that cannot write and the engine
  enforces what this hook can only indicate. An operator who wants certainty instead of indication
  classifies the tool `MUTATE` and takes the approval on every query.

### `type: project-governance`: evidence on high-impact actions

Supplied by the `aegis-mcp-guardrails` jar through ServiceLoader. The runnable `aegis-gateway` jar
bundles it. The Knox extension does not, so under Knox copy the jar next to the extension; without it
this type is unknown and startup fails.

| Setting | Default | Meaning |
| --- | --- | --- |
| `requireEvidenceFor` | `[]` | Tool classes (`READ`, `MUTATE`, `DESTRUCTIVE`) that must carry an evidence reference |
| `evidenceArgument` | `evidenceId` | Argument holding the reference. Must be an identifier |
| `minEvidenceLength` | 8 | Shortest accepted reference, 1 to 512. The longest is always 512 |
| `protectedResources` | `[]` | Resource globs denied to every caller and class, for example `project:governance:*` |

A protected resource is denied with `POLICY_DENIED`. A missing, short, overlong or malformed reference
(control characters or `..`) is denied with `INVALID_INPUT`. The hook checks the shape of the
reference only: it does not fetch the evidence, decide whether it is sufficient, or replace policy,
approval and dry-run receipts, which all still have to allow the call. The reference is an ordinary
tool argument, so declare it in the tool's `inputSchema`.

#### Illustrative Apache-project governance profile

[`apache-project-governance.example.yaml`](../adopters/profiles/apache-project-governance.example.yaml) is an illustrative Aegis profile for a project community to review and adapt. It is not an ASF policy, an ASF release rule, legal advice, or a statement that an adopting project complies with a regulation. Each community must replace the example identities, groups, sources, tools, endpoints, resources, evidence rules, and operational controls with values it approves.

| Governance theme | Aegis control in the example | What a project decides |
| --- | --- | --- |
| Traceability | `audit.fsync: true`, hash-chained decision records, argument digests | retention, external anchoring, and verifier access |
| Transparency | explicit source ID, schemas, typed resources, and source trust state | exposed operations, owners, and review evidence |
| Human oversight | independent `project-approvers`; approval plus receipt for destructive publication | roles, separation of duties, and release process |
| Robustness | default deny, narrow access, bounded I/O, screening, and rate budget | thresholds, classifications, and risk controls |
| Source review | `sources[].trust` and quarantine evidence references | fresh target evidence and admission authority |
| High-impact evidence | `project-governance` hook requires an `evidenceId` for configured classes | what the reference means and who validates it |

The profile uses `aegis-mcp-guardrails` for the hook and `aegis-state-derby` for durable local state; the own-host runnable jar bundles both. In an optional Knox deployment, add those jars beside the extension when this profile is used. `ApacheProjectGovernanceProfileTest` builds the profile with the real loader, checks tool visibility for contributor and maintainer roles, and verifies that Derby state is created beside the profile. The guardrail test suite verifies hook decisions separately. No test contacts a backend.

The profile deliberately uses the `.invalid` top-level domain, so it cannot point at a live project system. It borrows broad operational themes from Regulation (EU) 2024/1689, including record keeping, transparency, human oversight, and robustness. An MCP gateway profile cannot determine legal applicability or compliance. See the official [Regulation (EU) 2024/1689 text](https://eur-lex.europa.eu/eli/reg/2024/1689/oj/eng) for the regulation itself. Content was rephrased for compliance with licensing restrictions.

Before adapting the profile, obtain community agreement on action classes and resource taxonomy; bind groups to a real identity provider; replace the illustrative source with a real endpoint and retained target evidence; keep release mechanics under project review; and use the profile as input to security and legal review rather than a substitute for either.

## `host` (the gateway's own front door only)

| Key | Default | Meaning |
| --- | --- | --- |
| `listen.host` / `port` / `mcpPath` / `adminPath` / `threads` / `maxRequestBytes` | 127.0.0.1 / 8080 / /mcp / /aegis/v1 / 32 / 1 MiB | Listener address, endpoint paths, worker threads and request bound |
| `tls.keystore` / `keystorePasswordEnv` / `keystoreType` | - / - / PKCS12 | HTTPS keystore. Without it the listener speaks plain HTTP |
| `tls.clientAuth` / `clientCaFile` | `none` / - | `require` turns on mutual TLS: the handshake fails for a client without a certificate that chains to the PEM bundle in `clientCaFile` (only that bundle is trusted, not the JDK defaults). Needs `tls.keystore` |
| `identity.type` | `jwt` | `jwt`, `static` or `spiffe`; there is no anonymous mode |
| `identity.spiffe` | - | `trustDomain` plus `ids[]: {id, actor, groups}`. Needs `tls.clientAuth: require`; `clientCaFile` is the SPIFFE trust bundle. The verified leaf must be an X.509-SVID: not a CA, exactly one URI SAN, a `spiffe://<trustDomain>/<path>` ID with no port, user-info, query, fragment or empty, `.` or `..` segments, and, when key usage is present, `digitalSignature` without `keyCertSign`. An ID that is valid but not listed gets 401. The SVID is read once per request from the TLS session; this host does not fetch SVIDs from a SPIRE agent |
| `identity.jwt` | see meaning | `issuer`, `audience` (both required), `algorithms` [RS256], `jwksUri`, `hmacSecretEnv`, `groupsClaim` groups, `subjectClaim` sub, `clockSkewSeconds` 60, `jwksTtlSeconds` 3600, `jwksRefreshCooldownSeconds` 30 |
| `identity.static.tokens[]` | - | `{tokenEnv or sha256, actor, groups}`. A token read from `tokenEnv` must be at least 16 characters; only its SHA-256 digest is kept |
| `oauth.resource` / `authorizationServers` / `scopesSupported` / `documentation` | - | Off unless `resource` is set. Publishes RFC 9728 protected-resource metadata at `/.well-known/oauth-protected-resource` plus the resource path (unauthenticated by design, like `/healthz`), and adds `resource_metadata` to every 401 `WWW-Authenticate` challenge. `authorizationServers` and `documentation` must be https (http only for loopback), because clients open them. Setting any of the other `oauth` keys without `resource` stops startup, because nothing would be published |
| `oauth.allowAudienceMismatch` | `false` | `oauth.resource` must equal `identity.jwt.audience`, because MCP requires audience-bound tokens. Set `true` only when your authorization server uses a different audience value on purpose |
| `stdio` | - | `{actor, groups}` for `--stdio` |
| `metrics.path` / `metrics.allowedGroups` | `/metrics` / `[]` | `[]` means any authenticated caller. Same authentication as `/mcp` |
| `routes.mcpGroups` / `routes.adminGroups` | `[]` / `[]` | Own-host perimeter groups. When non-empty, a caller must belong to one before the listener reads an MCP/admin request body. Tool and resource authorization still runs in the governance engine. |
| `routes.mcpScopes` / `routes.adminScopes` / `metrics.requiredScopes` | `[]` | OAuth scopes a JWT must carry to reach that route (all of them, exact and case sensitive). Read from the `scope` claim, or from `scp` only when `scope` is absent. A valid token without them gets 403 with `WWW-Authenticate: Bearer error="insufficient_scope", scope="..."`, and the 401 challenge names them. Needs `identity.type: jwt`; each scope must be listed in `oauth.scopesSupported` when that list is set. Scopes are checked in addition to route groups, never instead of them. |
| `cors.allowedOrigins` | `[]` | Exact browser origins. Empty disables CORS/preflight. Wildcards are refused. `server.allowedOrigins` remains the common MCP/admin origin admission check for all hosts. |
| `cors.allowedMethods` / `allowedHeaders` | MCP defaults when CORS is enabled | Exact methods/header names allowed by preflight. |
| `cors.exposedHeaders` / `maxAgeSeconds` / `allowCredentials` | `[]` / 600 / false | Response headers exposed to browser code, cached preflight lifetime (0 to 86400), and explicit credential sharing. Credentials need at least one exact origin. |
