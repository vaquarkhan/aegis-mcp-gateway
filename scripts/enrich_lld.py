#!/usr/bin/env python3
"""Enrich LLD-apache-mcp-gateway.md with implementation details (UTF-8, no BOM, no em dashes)."""
from pathlib import Path

ROOT = Path(r"c:\Users\Administrator\Downloads\aegis-mcp-gateway")
OUT = ROOT / "docs" / "LLD-apache-mcp-gateway.md"

# Read existing file to preserve mermaid blocks exactly
existing = OUT.read_text(encoding="utf-8")

# Split by ## headers while keeping separators
import re

parts = re.split(r"(?=^## )", existing, flags=re.M)
# parts[0] is title/intro
intro = parts[0]
sections = parts[1:]

impl = {
    "1. System context": """
### Implementation notes

Codename in code and artifacts is **Aegis** (`aegis-mcp-gateway` 0.1.0) until any ASF acceptance.
The single process entry point is `io.github.vaquarkhan.aegis.core.boot.GatewayBootstrap`.

| External dependency | Status in 0.1.0 | Code |
| --- | --- | --- |
| Agent / operator MCP client | Implemented | stdio or streamable HTTP `/mcp` |
| OAuth 2.1 auth server | Stub (fail-closed) | `OAuthResourceFilter` returns 401 until JWKS verification lands |
| CIMD issuer | Stub (fail-closed) | `CimdVerifier`; HTTP mode with `authMode=cimd` refuses to start |
| SPIFFE / SPIRE | Stub (fail-closed) | `SpiffeMtls`; HTTP mode with `authMode=spiffe` refuses to start |
| PDP (Cedar / OPA) | Stub (deny-all) | `CedarPdp`, `OpaPdp`; production path uses `BuiltinPolicyEngine` |
| Secret store | Partial | Outbound headers from token file / `CredentialResolver`; no Vault client yet |
| OpenTelemetry collector | Not wired | Prometheus text via `Metrics` + `/metrics`; OTel spans are a later release |
| Flink / Kafka / Spark / Iceberg | Adapters present | Flink REST/SQL live; others stub backends where a live cluster is required |

stdout is reserved for MCP JSON-RPC. All logs go to stderr through Logback
(`mcp-gateway-core/src/main/resources/logback.xml`).
""",
    "2. Container view": """
### Implementation notes

One JVM, six Maven modules:

| Module | Artifact | Role |
| --- | --- | --- |
| Parent | `aegis-mcp-gateway` | BOM, plugin versions, Java 17 |
| Core | `mcp-gateway-core` | SPI, auth, chain, config, transport, boot |
| Flink | `mcp-adapter-flink` | Streaming taxonomy; REST + SQL Gateway clients |
| Kafka | `mcp-adapter-kafka` | Messaging taxonomy; AdminClient planned |
| Spark | `mcp-adapter-spark` | Batch taxonomy; History JDK HTTP; Livy planned |
| Iceberg | `mcp-adapter-iceberg` | Lakehouse taxonomy; REST catalog client |
| Dist | `mcp-gateway-dist` | Shade jar `aegis-mcp-gateway-0.1.0-all.jar`, Docker, Helm |

Request path inside the process:

1. Transport (`StdioBoot` or `StreamableHttpTransport` + Jetty 12 ee10)
2. Auth filter (`BearerAuthFilter`, `OAuthResourceFilter`, or refuse for CIMD/SPIFFE)
3. MCP SDK `tools/call` handler in `GatewayBootstrap`
4. `InterceptorChain.execute(ToolDef, CallContext)`
5. Adapter backend `Function<CallContext, String>` on `TimeoutExecutor`
6. Observers (`Trace`, `Metrics`, `AuditLog`) on every outcome
""",
    "3. Gateway core component view": """
### Implementation notes

Base package: `io.github.vaquarkhan.aegis.core`.

| Package | Key types | Notes |
| --- | --- | --- |
| `auth` | `CallerIdentity`, `CallerContext`, `BearerAuthFilter`, `TokenRegistry`, `NonceStore`, `OAuthResourceFilter`, `CimdVerifier`, `SpiffeMtls` | ThreadLocal identity; token file hashed inbound |
| `authz` | `PolicyDecisionPoint`, `BuiltinPolicyEngine`, `CedarPdp`, `OpaPdp` | `MCP_GW_PDP=builtin\\|cedar\\|opa` |
| `interceptor` | `InterceptorChain`, `ArgumentSanitizeMutator`, `Mutator`, `Validator`, `Observer`, `Decision`, `Phase`, `Severity` | Ten hard-coded steps plus pluggable mutators/observers |
| `governance` | `Exposure`, `Scope`, `Approval` (+ `ApprovalTokens` alias), `RateLimiter`, `CircuitBreaker`, `EgressGuard`, `PromptInjectionGuard`, `OutputControls`, `TimeoutExecutor`, `SqlReadonlyGuard` | Steps 1-8 and 10 pool |
| `integrity` | `VrpValidator`, `DigestRegistry`, `ToolCatalogIntegrity` | Rug-pull digests; dry-run receipt VRP |
| `router` | `ToolManifestAggregator`, `TaxonomyRouter`, `RetrievalRouter` | Overlay authority-reduction only |
| `observability` | `Trace`, `AuditLog`, `Metrics` | MDC `trace`; SHA-256 audit chain; Prometheus |
| `finops` | `TokenBudget`, `SemanticCache` | Daily cap enforced; READ cache |
| `config` | `GatewayConfig`, `AdapterRegistry` | Env wins over `MCP_GW_CONFIG` YAML |
| `yaml` | `YamlManifestLoader` | SafeConstructor; tool overlays |
| `transport` | `StreamableHttpTransport`, `StdioBoot`, `TlsSettings`, `OpsServlet` | `/healthz` `/readyz` `/metrics` |
| `spi` | `EngineAdapter`, `ToolDef`, `ResourceDef`, `CallContext`, ... | No MCP SDK types in SPI |
| `boot` | `GatewayBootstrap` | Main class for the shaded jar |
| `util` | `Inputs` | `requireId`, `requireTopic`, `jsonEscape`, ... |

Wiring order in `GatewayBootstrap.run()`: `GatewayConfig.fromEnv` -> `AdapterRegistry.discover` ->
`ToolManifestAggregator.aggregate` -> build chain -> register exposed tools -> start transport.
Shutdown calls `OpsServlet.markNotLive()` before draining the pool.
""",
    "4. Class model: adapter SPI and interceptor framework": """
### Implementation notes (SPI)

All SPI types live under `io.github.vaquarkhan.aegis.core.spi` and are plain Java (no MCP SDK).

```text
EngineAdapter
  engineId() / taxonomyClass()
  tools(cfg) / resources(cfg)
  readOnlyGuard() / credentialResolver()   // Optional
  egressAllowHosts(cfg)

ToolDef(name, ToolClass, description, inputSchemaJson, backend)
ResourceDef(uri, name, mimeType, read, redact)
CallContext(toolName, cls, arguments, caller, traceId, outboundCredential)
OutboundCredential(authorizationHeader)    // toString redacts
ToolClass = READ | MUTATE | DESTRUCTIVE
```

Adapters are discovered with `ServiceLoader` via
`META-INF/services/io.github.vaquarkhan.aegis.core.spi.EngineAdapter`, then filtered by
`MCP_GW_ADAPTERS`. Core never depends on engine client libraries.

### Implementation notes (interceptor framework)

| LLD type | Java type | Behavior |
| --- | --- | --- |
| `Interceptor` | `interceptor.Interceptor` | `name()`, `phase()`, `priority()` (default 100), `step()`, `apply(CallContext)` |
| `Mutator` | `interceptor.Mutator` | `mutate(CallContext)`; default `ArgumentSanitizeMutator` trims string args |
| `Validator` | `interceptor.Validator` | `validate(CallContext)` returns `Decision` |
| `Observer` | `interceptor.Observer` | `observe(ctx, decision)` / `onOutcome(ctx, decision, elapsedMillis)` |
| `Phase` | `MUTATION`, `VALIDATION`, `OBSERVATION` (+ deprecated `PRE`/`EXECUTE`/`POST` aliases) |
| `InterceptorChain` | `execute(ToolDef, CallContext)` plus `runInbound` / `runOutbound` | |
| `Decision` | record `(allowed, code, step, severity, message)` | field name is `allowed` |

The production chain still hard-codes steps 1-10 for deterministic security ordering. Mutators run
before `preflight`. Observers cannot change the decision.
""",
    "5. Class model: governance and auth": """
### Implementation notes

**GatewayBootstrap** (`boot.GatewayBootstrap`): `VERSION=0.1.0`, `SERVER_NAME=aegis-mcp-gateway`,
`MCP_ENDPOINT=/mcp`. Builds MCP tools with
`Tool.builder().inputSchema(McpJsonMapper, String)` and
`new JacksonMcpJsonMapperSupplier().get()`. Never passes a Jackson `ObjectMapper` into a transport.

**GatewayConfig** (`config.GatewayConfig`): `fromEnv()`, optional YAML via `MCP_GW_CONFIG`,
`validate()` fail-fast, `adapterProperty(key, default)` for engine endpoints,
`writesUnlocked()` requires write flag and non-blank approval secret.

**CallerIdentity** (`auth.CallerIdentity`): `subject`, `tenant`, `scopes`, `jobsAllow`,
`jarsAllow`, `readonly`, `outboundAuthHeader`; helpers `jobAllowed`, `jarAllowed`, `scopeAllowed`,
`callerId()` (= subject). Empty allow lists fail closed.

**TokenRegistry** line formats:

- LLD: `callerId:sha256Hex:jobsCsv:jarsCsv:readonly[:outboundAuth]`
- Legacy: `callerId:sha256Hex:scopesCsv:readonly` (scopes mirrored onto jobs and jars)

Inbound tokens are hashed only (SHA-256). Lookup uses constant-time compare.

**Approval**: class `governance.Approval` implements mint/verify; `ApprovalTokens` is a thin
alias for the LLD name. Token shape:
`base64url(tool|scope|expMillis|nonce).base64url(HMAC-SHA256)`. CLI mint prints only the token
on stdout.

**PolicyDecisionPoint**: `allows(CallerIdentity, String tool, Map args)` with default
`allows(CallContext)`. Builtin policy file lines: `deny tool <glob>`, `deny job <glob>`; missing
file fails closed. Cedar/OPA stubs always deny.

**RateLimiter**: `allow(callerId)` with a `ConcurrentHashMap` of one-second fixed windows.
**CircuitBreaker**: per-tool CLOSED / OPEN / HALF_OPEN with a single half-open probe.
**OutputControls**: `boundAndRedact` = bound first, then DLP redact (secrets, JWT, PEM, email, Bearer).
""",
    "6. Sequence: governed tools/call": """
### Implementation notes

Mapped call path:

1. HTTP: Jetty serves `HttpServletStreamableServerTransportProvider` at `/mcp/*` behind the auth
   filter. Stdio: `StdioServerTransportProvider(json)`.
2. Auth sets `BearerAuthFilter.ATTR_CALLER` and `CallerContext` ThreadLocal.
3. Tool handler builds `CallContext`, resolves optional `CredentialResolver` outbound header,
   then `chain.execute(tool, ctx)`.
4. Mutators (`ArgumentSanitizeMutator`) then `preflight` steps 1-9.
5. Optional `SemanticCache` hit for READ tools.
6. Pre-flight `TokenBudget` check when enabled.
7. `TimeoutExecutor.submit(backend, caller)` then `future.get(toolTimeoutMillis)`.
8. On success: `breaker.recordSuccess`, `output.boundAndRedact`, cache put, budget consume
   (deny `BUDGET_EXCEEDED` if over), optional VRP dry-run receipt append.
9. Observers always run in `finish`.

Denial body format returned to the model: `denied: <CODE>` (and timeout detail when applicable).
Audit outcome format: `DENIED:<CODE>:step<N>` or `ALLOWED`.
""",
    "7. Sequence: tools/list with taxonomy routing": """
### Implementation notes

At startup `ToolManifestAggregator`:

1. Collects `ToolDef` / `ResourceDef` from each enabled adapter.
2. Applies `tools.yaml` overlay (rename, description, force lower class, digest pin, drop). Overlay
   cannot raise `ToolClass` or invent a backend.
3. Pins digests via `ToolCatalogIntegrity` / `DigestRegistry` (rug-pull defense).
4. Builds `TaxonomyRouter` (`toolName -> engineId -> taxonomyClass`).
5. Unions `egressAllowHosts`.

`GatewayBootstrap.buildToolSpecs` then drops tools that `Exposure.isExposed` rejects, so
`MUTATE`/`DESTRUCTIVE` never appear in `tools/list` when writes are locked.

`RetrievalRouter` is optional intent pruning (`prune(authorizedTools, intentHint)`); it never
widens the authorized set. Full per-caller dynamic `tools/list` filtering for multi-token
registries is planned; registration-time exposure is the 0.1.0 control.
""",
    "8. Sequence: OAuth 2.1 resource-server validation": """
### Implementation notes

Class: `auth.OAuthResourceFilter`.

Config: `MCP_GW_AUTH_MODE=oauth`, `MCP_GW_OAUTH_ISSUER`, `MCP_GW_OAUTH_AUDIENCE`,
`MCP_GW_OAUTH_JWKS_URL` (optional until verification lands), `MCP_GW_OAUTH_REQUIRED_SCOPE`.

**0.1.0 behavior:** the filter logs that it is a stub and returns **401** for every request. It
never calls `chain.doFilter`. `verifyClaims(compactJwt)` throws `UnsupportedOperationException`.
Issuer and audience must still be configured or startup fails. URLs are scheme-checked in
`GatewayConfig.validate()`.

Planned (DESIGN open items): JWKS fetch with rotation, RS256/ES256 verify, `iss` / `aud` /
`exp` / `nbf`, scope mapping onto `CallerIdentity`.
""",
    "9. Sequence: CIMD stateless client registration": """
### Implementation notes

Class: `auth.CimdVerifier`.

Config: `MCP_GW_AUTH_MODE=cimd`, `MCP_GW_CIMD_METADATA_URL` (https URL validated at startup).

**0.1.0 behavior:** compile-safe stub. `GatewayBootstrap.buildAuthFilter` refuses to start HTTP
in CIMD mode rather than accepting unverified clients. Document fetch, `client_id` URL match,
redirect allowlist and PKCE exchange remain future work (authorization server side plus gateway
token validation per section 8).
""",
    "10. Sequence: SPIFFE workload identity over mTLS": """
### Implementation notes

Class: `auth.SpiffeMtls`.

Config: `MCP_GW_AUTH_MODE=spiffe`, `MCP_GW_SPIFFE_TRUST_DOMAIN`,
`MCP_GW_SPIFFE_SOCKET` (preferred) or legacy `MCP_GW_SPIFFE_WORKLOAD_API`.

**0.1.0 behavior:** stub. HTTP + spiffe mode refuses to start. SVID attestation, trust-domain
validation and SPIFFE-ID to `CallerIdentity` mapping are not implemented.
TLS for the HTTP listener itself is separate (`MCP_GW_HTTP_TLS_*` + `TlsSettings` on Jetty).
""",
    "11. Sequence: approval for a destructive tool": """
### Implementation notes

Classes: `governance.Approval`, `governance.ApprovalTokens` (alias), `auth.NonceStore`.

Mint CLI:

```bash
java -cp mcp-gateway-core/target/classes \\
  io.github.vaquarkhan.aegis.core.governance.Approval \\
  \"$MCP_GW_APPROVAL_SECRET\" stop_job job-42 300
```

(or the `ApprovalTokens` main entry). Prints a single token line on stdout.

Verify path in `InterceptorChain` step 4 for non-READ tools:

1. Read `approvalToken` from arguments.
2. Scope = `Scope.approvalScopeOf(ctx)` (`jobId` / `jarId` / topic / ... or `*`).
3. HMAC constant-time compare, tool match, scope match, not expired.
4. `NonceStore.useOnce(nonce, exp)` prevents replay.

Empty secret: verify always false. Enabling writes without secret fails at `validate()`.
""",
    "12. State machine: per-tool circuit breaker": """
### Implementation notes

Class: `governance.CircuitBreaker(failureThreshold, resetMillis)`.

- Config: `MCP_GW_BREAKER_FAILURES` (default 5), `MCP_GW_BREAKER_RESET_MS` (default 30000).
- State is per tool name.
- `TIMEOUT`, `BACKEND_ERROR`, and executor rejection call `recordFailure`.
- `INVALID_INPUT` does **not** trip the breaker.
- HALF_OPEN admits exactly one probe (`halfOpenInFlight` claim); success -> CLOSED, failure -> OPEN.
- Open tools deny with `BREAKER_OPEN` (step 7) before the backend is touched.
""",
    "13. State machine: approval token lifecycle": """
### Implementation notes

Same as section 11. States map to code as:

| State | Code path |
| --- | --- |
| Minted | `Approval.mint(tool, scope, ttlMillis)` |
| Presented | argument `approvalToken` on `tools/call` |
| Verified | HMAC + tool + scope + expiry OK |
| Consumed | `NonceStore.useOnce` first success |
| Rejected | bad sig / wrong tool / wrong scope / expired / replay -> `APPROVAL_REQUIRED` |

Default TTL: `MCP_GW_APPROVAL_TTL_MS` (300000).
""",
    "14. Sequence: VRP data-integrity gate on commit": """
### Implementation notes

Class: `integrity.VrpValidator`. Enabled with `MCP_GW_VRP_ENABLED` (and receipt TTL).

**0.1.0 design in code:** dry-run receipt scheme (compatible with Iceberg maintenance):

1. Destructive (and optionally mutating) call with `dryRun=true` records a receipt bound to
   caller + tool + argument fingerprint.
2. Real run must present that receipt; otherwise `VRP_FAILED` (step 9).
3. Iceberg tools: `expire_snapshots`, `remove_orphan_files`, `rewrite_data_files`, `drop_table`,
   `commit_transaction`, plus READ companion `dry_run_maintenance`.

The offline cryptographic `verify_vrp(source, sink, evidence)` path returning PASS/FAIL/UNVERIFIED
and JSON-RPC `-32603` is author-owned / optional and not yet wired; the receipt gate is what ships
so the pipeline works before an external verifier exists.
""",
    "15. Data model: audit chain, digest registry, token registry": """
### Implementation notes

**AuditLog** (`observability.AuditLog`): in-memory ring (500). Each entry stores `record`,
`hash`, `prevHash` where `hash = SHA-256(prevHash | record)`. `verifyChain()` validates continuity
from the oldest retained entry. Implements `Observer` so every allow/deny is appended.

**DigestRegistry** / **ToolCatalogIntegrity**: digest over tool name, class, description, and
whitespace-normalized `inputSchemaJson`. First sight pins; later mismatch is a rug-pull error at
aggregation / verify time. Overlay pins from `tools.yaml` are compared too.

**TokenRegistry**: see section 5. Hashes only; never stores raw bearer secrets.

**CallerIdentity**: see section 5. Subject is the audit `caller` field.
""",
    "16. Interceptor directionality": """
### Implementation notes

Inbound (`InterceptorChain.execute`):

1. `MUTATION`: registered mutators (`ArgumentSanitizeMutator` by default).
2. `VALIDATION`: steps 1-9 in `preflight` (first denial wins).
3. Budget / cache checks.
4. `EXECUTE`: backend on `TimeoutExecutor`.
5. `OBSERVATION`: `Trace`, `Metrics`, `AuditLog`.

Outbound:

1. `OutputControls.bound(body)` truncates to `MCP_GW_MAX_BYTES` with `...<truncated>`.
2. `OutputControls.redact(bounded)` applies DLP patterns.
3. Combined as `boundAndRedact` so a secret cannot sit across the truncation boundary.

Helpers: `runInbound(tool, ctx)` -> preflight Decision; `runOutbound(body)` -> redacted string.
""",
    "17. Sequence: cross-engine anomaly resolution": """
### Implementation notes

This is the product narrative across adapters, not a single API. In 0.1.0:

| Step | Tool | Adapter status |
| --- | --- | --- |
| Inspect table metadata | `get_table` / `get_table_metadata` | Iceberg REST client (JDK HTTP); redacts vended credentials |
| Job exceptions | `get_job_exceptions` | Flink REST live |
| Schema registry | `query_schema_registry` | Kafka stub body until Admin/SR wired |
| Stop job | `stop_job` + approval | Flink REST live |
| Reset offsets | `reset_offsets` + approval | Kafka stub |
| Commit / maintenance | `commit_transaction` / expire / rewrite + VRP | Iceberg stub mutate; VRP receipt gate in core |

Every hop still passes the same interceptor chain, audit, and output redaction.
""",
    "18. Deployment: highly available topology": """
### Implementation notes

Shipped packaging:

- Runnable: `mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar`
- Docker: `mcp-gateway-dist/src/main/docker/Dockerfile` (Temurin 17 JRE)
- Helm: `mcp-gateway-dist/src/main/helm/aegis-mcp-gateway/`

**0.1.0 process state is in-memory:** `RateLimiter`, `NonceStore`, `AuditLog`, `SemanticCache`.
Horizontal replicas do not yet share rate or nonce state. LLD Redis / durable audit boxes are the
target HA topology; wire them before multi-replica approval replay protection is required.

Replicas are otherwise stateless MCP servers (stdio or HTTP). Prefer TLS and `tokenfile` or
future OAuth at the edge. Ops probes: `/healthz`, `/readyz` (fails after `markNotLive`),
`/metrics` (Prometheus). Auth filter applies only to `/mcp/*`.
""",
    "19. Threading and concurrency model": """
### Implementation notes

Class: `governance.TimeoutExecutor`.

| Setting | Value |
| --- | --- |
| Core pool | 4 |
| Max pool | 32 |
| Queue | 128 (`LinkedBlockingQueue`) |
| Threads | daemon, named `aegis-backend-*` |
| Rejection | `AbortPolicy` -> `BACKEND_ERROR` |
| Deadline | `future.get(MCP_GW_TOOL_TIMEOUT_MS)` (default 30000, min 50) |

Validators run on the request/MCP handler thread so `CallerContext` is visible. The pool
propagates `CallerContext` onto worker threads and clears it in `finally`.

Note: 0.1.0 uses one process-wide pool. LLD \"per-adapter pool\" isolation is a follow-up so one
slow engine cannot fill the shared queue.
""",
    "20. Denial and error code catalog": """
### Implementation notes

Constants live on `interceptor.Decision` (`NOT_EXPOSED`, ... `BUDGET_EXCEEDED`). Steps are
`STEP_EXPOSURE=1` ... `STEP_EXECUTE=10`.

| Code | Step | Implemented by |
| --- | --- | --- |
| NOT_EXPOSED | 1 | `Exposure` |
| READONLY_CALLER | 2 | chain + `CallerIdentity.readonly` / `MCP_GW_READONLY_CALLER` |
| SCOPE_DENIED | 2 | `Scope` + `jobAllowed` / `jarAllowed` / `scopeAllowed` |
| POLICY_DENIED | 3 | `PolicyDecisionPoint` |
| APPROVAL_REQUIRED | 4 | `Approval` |
| EGRESS_DENIED | 5 | `EgressGuard` (metadata IPs always denied) |
| RATE_LIMITED | 6 | `RateLimiter.allow(callerId)` |
| BREAKER_OPEN | 7 | `CircuitBreaker` |
| PROMPT_INJECTION | 8 | `PromptInjectionGuard` |
| VRP_FAILED | 9 | `VrpValidator` |
| INVALID_INPUT | 10 | `Inputs.InvalidInput` (no breaker trip) |
| TIMEOUT | 10 | `TimeoutException` (trips breaker) |
| BACKEND_ERROR | 10 | other backend failures / reject (trips breaker) |
| BUDGET_EXCEEDED | FinOps | `TokenBudget` (pre/post execute) |
""",
    "21. Key configuration surface": """
### Implementation notes

`GatewayConfig.fromEnv()` layers optional YAML (`MCP_GW_CONFIG` / `gateway-default.yaml`) under
environment overrides. Extra keys used in code beyond the table above:

| Env key | Purpose |
| --- | --- |
| MCP_GW_HTTP_PORT | bind port (default 8090) |
| MCP_GW_HTTP_BEARER_TOKEN | single-token HTTP auth |
| MCP_GW_AUTH_TOKENS_FILE | multi-caller hashed token registry |
| MCP_GW_HTTP_TLS_KEYSTORE / _PASSWORD / _TYPE | TLS material |
| MCP_GW_APPROVAL_TTL_MS | approval lifetime |
| MCP_GW_BREAKER_FAILURES / _RESET_MS | breaker |
| MCP_GW_DLP_ENABLED | output redaction toggle |
| MCP_GW_PROMPT_INJECTION_ENABLED | injection guard toggle |
| MCP_GW_VRP_ENABLED / _RECEIPT_TTL_MS | VRP gate |
| MCP_GW_SEMANTIC_CACHE_TTL_MS | READ cache |
| MCP_GW_TOOLS_ALLOWED | CSV allow list (default read profile) |
| MCP_GW_READONLY_CALLER | force readonly |
| MCP_GW_POLICY_FILE | builtin PDP rules |
| MCP_GW_LOG_LEVEL | Logback root level |
| MCP_GW_SHUTDOWN_TIMEOUT_MS | drain budget |
| FLINK_REST_URL / MCP_FLINK_* / SPARK_* / ICEBERG_* / kafka bootstrap | adapter endpoints via `adapterProperty` |

`validate()` rejects unknown transport/auth/pdp, writes without secret, HTTP without inbound auth
config, TLS without keystore+password, out-of-range limits, and non-http(s) OAuth/CIMD URLs.
""",
    "22. Per-adapter endpoint map": """
### Implementation notes

#### Flink (`mcp-adapter-flink`, taxonomy `streaming`)

Manifest: `adapters/flink/tools.yaml`. Factory: `FlinkToolFactory`. Clients: `FlinkRestClient`,
`SqlGatewayClient`, `SqlReadonlyGuard`, `OutboundAuth`.

Full READ set also includes `get_job_status`, `get_job_metrics`, `list_checkpoints`, `list_jars`,
`get_cluster_info`, `list_taskmanagers`, `get_job_config`, `get_flink_config`. MUTATE also
`upload_jar`. DESTRUCTIVE also `run_jar`.

#### Kafka (`mcp-adapter-kafka`, taxonomy `messaging`)

Tools as in the table; backends return TODO JSON until AdminClient / Schema Registry HTTP is
wired. Identifiers validated with `Inputs.requireTopic` / `requireId`.

#### Spark (`mcp-adapter-spark`, taxonomy `batch`)

`SparkHttpClient` for History Server. `run_sql_readonly` uses core `SqlReadonlyGuard`.
`submit_batch` / `kill_application` stubbed. History HTTP failures propagate (breaker-visible).

#### Iceberg (`mcp-adapter-iceberg`, taxonomy `lakehouse`)

`IcebergRestClient` redacts credential/token/access-key fields. Tools include
`commit_transaction`, `get_table_metadata` (alias), maintenance DESTRUCTIVE set, and
`dry_run_maintenance`. Mutating REST calls stubbed where a live catalog is required.
""",
    "23. Notes for reviewers": """
### Implementation status summary (0.1.0)

| Area | Status |
| --- | --- |
| Interceptor chain steps 1-10 + deny codes | Implemented and tested |
| Flink adapter + embedded HTTP fakes | Implemented |
| Kafka / Spark / Iceberg scaffolds + YAML | Present; live mutate backends partial |
| Builtin PDP, approvals, breaker, egress, DLP | Implemented |
| OAuth / CIMD / SPIFFE verification | Fail-closed stubs |
| Cedar / OPA evaluation | Deny-all stubs |
| OpenTelemetry spans | Not shipped (Prometheus + audit yes) |
| Redis shared rate/nonce / durable audit | Not shipped |
| Shade jar / Docker / Helm | Shipped under `mcp-gateway-dist` |

Build: `mvn clean verify`. Run:

```bash
MCP_GW_ADAPTERS=flink FLINK_REST_URL=http://localhost:8081 \\
  java -jar mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```
""",
}


def section_key(header_line: str) -> str | None:
    # "## 1. System context (C4 level 1)\n" -> match prefix keys
    m = re.match(r"^##\s+(\d+)\.\s+(.+?)(?:\s*\(|$)", header_line.strip())
    if not m:
        return None
    num, title = m.group(1), m.group(2).strip()
    for k in impl:
        if k.startswith(num + "."):
            return k
    # fuzzy: number only
    for k in impl:
        if k.split(".", 1)[0] == num:
            return k
    return None


out_chunks = [intro.rstrip() + "\n"]
# Refresh intro to mention implementation notes
if "Implementation notes" not in intro:
    out_chunks = [
        "# Low-level design: Apache MCP Governance Gateway\n\n"
        "Companion to `DESIGN-apache-mcp-gateway.md`. This document adds the detailed low-level\n"
        "design with Mermaid diagrams and **implementation notes** that map each diagram to the\n"
        "Java packages and classes in this repository (Aegis MCP Gateway 0.1.0). Read the DESIGN\n"
        "doc first for the decisions and rationale; this doc shows how they fit together at the\n"
        "class and call level. No em dashes. Author-owned components are labeled and optional.\n\n"
        "Codename in code is Aegis until any ASF acceptance. Diagrams may say Apache MCP Governance\n"
        "Gateway as the proposed project name.\n\n"
    ]

for sec in sections:
    key = section_key(sec.splitlines()[0] if sec else "")
    body = sec.rstrip() + "\n"
    if key and key in impl:
        # append implementation after diagrams if not already present
        if "### Implementation notes" not in body:
            body = body.rstrip() + "\n" + impl[key].rstrip() + "\n"
    out_chunks.append(body)
    if not body.endswith("\n"):
        out_chunks.append("\n")

text = "\n".join(chunk if chunk.endswith("\n") else chunk + "\n" for chunk in out_chunks)
# normalize: collapse excessive blank lines to max 2
text = re.sub(r"\n{3,}", "\n\n", text)
if text.count("\u2014") or text.count("\u2013"):
    raise SystemExit("em/en dash found")
OUT.write_text(text, encoding="utf-8", newline="\n")
print("wrote", OUT, "bytes", OUT.stat().st_size, "mermaid", text.count("```mermaid"), "impl", text.count("### Implementation"))
