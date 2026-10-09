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
# aegis-config

Turns one JSON or YAML document into a running governed gateway: engine, MCP handler, admin API,
catalog, metrics, tracer, and state. This is the module operators and most plugin authors touch
through configuration keys, not by editing Java.

**Dependencies:** `aegis-engine`, `aegis-mcp`, `aegis-observability-otlp`. SnakeYAML is optional and
used only for `.yaml` / `.yml` files.

## What it owns

- Strict configuration loading (unknown keys stop startup; enumerated values are closed lists).
- Built-in `http` and `mcp` sources, `remote` and `sql-readonly` hooks, OPA policy wiring.
- Static and RFC 8693 token-exchange credentials; `env` / `file` / `vault` secret providers.
- ServiceLoader factories: `ToolSourceFactory`, `GovernanceHookFactory`, `StateStoreFactory`,
  `SecretProviderFactory`.
- `OpenApiDraft`, behind the gateway's `--openapi-draft` option, which emits a reviewable deny-all
  draft from an OpenAPI document.

## How it works end to end

Everything happens in `GatewayFactory.build`, in this order. Relative paths resolve against the
file's directory. `BuildContext` carries what a host supplies: secret lookup, clock, DNS resolver,
extra hooks and audit sink, a credential override and a shared state store.

1. Load. `.yaml` and `.yml` are YAML, anything else is JSON. JSON goes through the engine's `Json`
   parser with `JsonLimits.CONFIG`, which rejects duplicate keys. YAML needs SnakeYAML on the
   classpath and uses its `SafeConstructor` with duplicate keys refused, at most 20 aliases, no
   recursive keys, depth 64 and an 8 MiB code-point limit. Dates and other special scalars are refused.
2. Strict view. The map is wrapped in a `ConfigNode`, which records every key it reads. `host` and
   `knox` are skipped and kept raw for the host (`Gateway.hostSection`).
3. Secrets. `secrets.source` picks one lookup for every later `...Env` name: `env` (default),
   `file` (`FileSecretProvider`), `vault` (`VaultKvSecretProvider`; `address` must pass the
   operator's `egress` list) or a `SecretProviderFactory` whose `type()` matches.
4. Identity and approval. `gateway.name` (default `aegis`), `access` groups, the HMAC key from
   `approval.keyEnv` or `keyFile` (32 bytes or more), `approval.requiredFor` (no READ) and `vrp`.
5. Signed server catalog. When the host sets a trust anchor (`REQUIRED_CATALOG_KEY`,
   `AEGIS_REQUIRE_SERVER_CATALOG_KEY` read from `BuildContext.env`, or the system property
   `aegis.requireServerCatalogKey`; an absolute path), `serverCatalog` is required and its
   `publicKeyFile` must be that key. When `serverCatalog` is set, `SignedServerCatalog.loadWithSerial`
   verifies it with `publicKeyFile` for this gateway name. An optional `breakGlass` order
   (`BreakGlassContainment`) needs a different key and may only name catalog servers. Their signed
   serials are checked in step 12.
6. Sources, in file order. With a catalog, each `id` must be listed and unique; a record that is not
   ACTIVE, or is in the break-glass order, is skipped with `skipAll()` and never started. For an
   ACTIVE record, `GatewayFactory.sourceDigest` of the block must equal the record's `sourceDigest`,
   and its pin names become the source's allow list of exposed tool names. Then it is built:
   - Common keys: `id` (`[a-z0-9][a-z0-9_-]{0,63}`), `type`, `namePrefix`, `timeoutMs`,
     `unlistedTools` (`EXPOSE` or `HIDE`), `defaultClass` (default `DESTRUCTIVE`), `trust.state`
     (`QUARANTINED` needs `trust.evidence`).
   - Credentials: static `none`, `bearer`, `basic` or `header` (`StaticCredentialProvider.Kind`),
     or `token-exchange` (`TokenExchangeCredentials`): needs `audience` or `resource`, and lists
     tools with a separate static `discovery` credential.
   - `mcp`: `HttpMcpClient` (`url`) or `StdioMcpClient` (`command`) in a `McpProxyToolSource`;
     resources, prompts and completions are off unless set. `http`: one `HttpToolExecutor` per
     tool in an `HttpToolSource`, no redirects, and every `{placeholder}` in `request.path` must
     be in `inputSchema.required`. Other types: the `ToolSourceFactory` whose `type()` matches.
   - Per-tool keys (`toolSettings`): `class` (required for `http` tools), `resources`, `dryRun`
     (`GATEWAY`, `ARGUMENT`, `TOOL`), `allowedGroups`, `actionScopes` (each an RFC 6749 scope
     token) and `timeoutMs`.
7. Egress. `EgressGuard` gets `egress.allowHosts`, `allowCidrs`, `schemes` (default `https`) and the
   host and scheme of each `mcp` `url`, `http` `baseUrl` and plugin `ToolSource.configuredEndpoints()`
   entry (for example `tcp://broker:9092` from `kafka-admin`). Every token-exchange `tokenEndpoint`
   must then pass it. Kafka listener addresses learned from an allowed bootstrap server are a later
   client topology hop and need Kafka listener configuration plus network policy.
8. Policy and controls. `policy.rules` become a `RulePolicy`; `policy.opa.url` adds an `OpaPolicy`
   after an egress check; two are combined with `RulePolicy.allOf`. Then `scopes`, `rateLimits`,
   `breaker`, `screening` and `output`.
9. Hooks. `remote` (`RemoteHook`, URL checked against egress) and `sql-readonly`
   (`SqlReadOnlyHook`, `settings.arguments` required) are built in; other types come from a
   `GovernanceHookFactory`. `BuildContext.extraHooks` are appended and always enforce.
10. Audit, catalog, server. `audit.file`, `fsync`, `includeArguments`, optional `checkpoint`. Then
    `CatalogIntegrity` from `catalog.pins`, a signed `catalog.manifest` (`SignedCatalogManifest`),
    `pinFile` and `mode` (`TOFU`, or `REQUIRE_PINS`, the default and only value with a server
    catalog, which also refuses `pinFile`, merges its started servers' pins and refuses any other
    pin). Then `server`, `metrics.enabled` and `tracing.otlp` (endpoint checked against egress).
11. State. `BuildContext.stateStore` wins, else `state.type`: `memory` or a `StateStoreFactory`.
12. `root.finish()` fails on unknown keys anywhere in the tree. Then `SignedSerial.accept` checks the
    signed `serial` of the server catalog, break-glass order and catalog manifest against the highest
    accepted one in the state store and records it (rollback protection). The state store is closed
    on failure.
13. Open and wire. The `HashChainFileAuditSink` journal opens (teed with `extraAuditSink`; both
    writes must succeed), then the OTLP exporter. Credentials are `credentialOverride`, or
    `StaticCredentialProvider` plus `SourceCredentialProvider` for token exchange. Then `ToolCatalog`,
    `ApprovalService`, `VrpService`, `GovernanceEngine` and, for MCP content, `ContentGovernor`.
14. Discovery. `catalog.load()` lists each source's tools and binds them: a quarantined source binds
    nothing, a catalog allow list drops unpinned names, `HIDE` drops unlisted tools, tools without a
    configured class get `defaultClass`, and an upstream `destructiveHint` can only raise the class.
15. Result. `McpServerHandler`, `AdminApi`, an optional refresher (`catalog.refreshSeconds`), and
    the `Gateway` with `engine()`, `mcp()`, `admin()`, `metrics()`, `tracer()` and `secrets()`.

```text
file -> ConfigLoader -> ConfigNode -> secrets -> gateway, access, approval, vrp
     -> serverCatalog + breakGlass verified (no source exists yet)
     -> sources[] (http | mcp | ToolSourceFactory, credentials, per-tool keys)
     -> egress -> policy + controls -> hooks -> audit, catalog, server, tracing
     -> state -> root.finish() (unknown keys stop here)
     -> open journal + tracer, wire engine -> catalog.load() -> Gateway
```

## A minimal configuration

Adapted from `yaml(...)` in [`GatewayFactoryTest`](src/test/java/io/github/vaquarkhan/aegis/config/GatewayFactoryTest.java)
(used by `yamlConfigBuildsAGovernedGatewayOverRestAndAnExistingMcpServer`), with a fixed port and
without the test's second (`mcp`) source.

```yaml
gateway: {name: test-gw}
access: {writeGroups: [operators], approverGroups: [approvers]}
approval: {keyEnv: AEGIS_SIGNING_KEY}
policy:
  rules:
    - {id: analysts-read, groups: [analysts], classes: [READ]}
    - {id: operators-write, groups: [operators], classes: [MUTATE, DESTRUCTIVE]}
egress: {allowCidrs: [127.0.0.0/8]}
audit: {file: audit/audit.jsonl}
sources:
  - id: rest
    type: http
    baseUrl: http://127.0.0.1:8080
    credentials: {type: bearer, tokenEnv: REST_TOKEN}
    tools:
      - name: list_tables
        description: List tables in a namespace.
        class: READ
        inputSchema: {type: object, properties: {namespace: {type: string}, pageSize: {type: integer}},
                      required: [namespace]}
        request: {method: GET, path: "/v1/namespaces/{namespace}/tables", query: {pageSize: "{pageSize}"}}
        response: {pointer: /items}
      - name: drop_table
        description: Drop a table.
        class: DESTRUCTIVE
        inputSchema: {type: object, properties: {namespace: {type: string}, table: {type: string}},
                      required: [namespace, table]}
        request: {method: DELETE, path: "/v1/namespaces/{namespace}/tables/{table}"}
        resources: [{type: "iceberg:table", pointers: [/namespace, /table]}]
```

- `gateway.name` names this gateway; approvals and signed files are bound to it. `access` lets
  `operators` call write tools and `approvers` decide approvals. With the default `secrets.source:
  env`, `AEGIS_SIGNING_KEY` (the HMAC key, 32 bytes or more) and `REST_TOKEN` must be set.
- `policy.rules` allow READ for `analysts` and writes for `operators`; with no rule and no `opa`,
  startup fails. `egress.allowCidrs` covers the loopback backend (the `baseUrl` host and `http`
  scheme are added). `audit.file` is the hash-chained journal, relative to the configuration file.
- The `http` source sends the service token, never the caller's. `list_tables` returns only
  `/items`; `drop_table` is DESTRUCTIVE with a typed resource. In the test, the drop is denied with
  `APPROVAL_REQUIRED` and never reaches the backend, and the journal verifies afterwards.

## Failure behaviour

Startup throws `ConfigException`, usually naming the key path, when:

- A YAML file is read without SnakeYAML on the classpath, or the document is invalid or repeats a key.
- A key is unknown (`config.access: unknown key(s) [writeGrups]`) or an enum value is off its list.
- A named secret is unset or empty under `secrets.source` (`... names NOT_SET, which is not set by
  secrets.source`), the secret source is unknown, Vault is unreachable or misconfigured, the
  approval key is under 32 bytes, or `approval.requiredFor` includes READ.
- An endpoint is not an absolute http(s) URL without user-info, or a Vault address, token endpoint,
  OPA URL, remote hook URL or OTLP endpoint is outside `egress`.
- The server catalog or break-glass order does not verify, a source is unlisted, changed or
  duplicated, the break-glass key equals the catalog key, the order has expired or names an unknown
  server, no source is left to start, or `catalog` tries to weaken signed pinning. A signed
  `catalog.manifest` likewise must verify and agree with inline pins.
- There are no sources or no policy, an `http` source has no tools, a path placeholder is not
  required, a source, hook or state type is unknown, a built-in hook type is also claimed by a
  factory, `sql-readonly` has no `arguments`, or `QUARANTINED` has no `evidence`.
- A dry run would run the real call: an `ARGUMENT` flag missing from the schema or template, or a
  `TOOL` preview that is not an explicitly READ tool of the same source.
- `audit.checkpoint` is out of range or points at the journal itself.

After validation, an audit journal that cannot be opened or a `catalog.load()` that throws stops
startup with the original exception, after closing what was already opened. A missing approval key
or empty `approverGroups` is not a refusal: a warning is logged and calls that need approval are
denied with `APPROVAL_REQUIRED`.

## Tests

| Test class | What it proves |
| --- | --- |
| `GatewayFactoryTest` | YAML build over REST and MCP, unknown keys, missing secrets, quarantine, dry-run checks, opt-in content, `actionScopes`, injection-safe templates |
| `HttpSourcesAndWiringTest` | Templates, credentials, response checks, teed audit sink, enumerated values, OPA egress |
| `ServerCatalogConfigTest` | Steps 5, 6, 10 and 12 with a catalog: tamper refusal, digests, unlisted and non-ACTIVE sources, pin allow lists, break-glass, host trust anchor, rollback protection |
| `AuditCheckpointAndManifestConfigTest` | Checkpoint files and limits, signed manifest pins |
| `DryRunPreviewToolTest` | `dryRun.mode: TOOL` on plugin sources runs the READ preview only |
| `credentials.TokenExchangeCredentialsTest` | RFC 8693 exchange, caching, fail-closed answers, startup checks |
| `hooks.RemoteHookTest`, `hooks.SqlReadOnlyHookTest` | Hook decisions, phases, modes, egress refusal, SQL statement rules |
| `secrets.SecretProvidersTest` | `file`, `vault` and plugin secret sources, strict settings |
| `policy.OpaPolicyTest`, `openapi.OpenApiDraftTest` | Only a strict boolean `true` from OPA allows; drafts are DESTRUCTIVE and deny-all |
| `secrets.VaultKvSecretProviderIT` | Real Vault-API server, only with `-Pvault-integration` |

`PreviewPluginToolSourceFactory` and `PropertiesFileSecretProviderFactory` are test plugins
registered under `src/test/resources/META-INF/services`.

## Developer instructions

```powershell
# Build and unit-test this module and its dependencies
mvn -B -pl aegis-config -am test

# Opt-in Vault/OpenBao secret integration (needs AEGIS_VAULT_ADDR and AEGIS_VAULT_TOKEN)
mvn -B -pl aegis-config -am test -Pvault-integration
```

When you change behavior:

1. Prefer a configuration key documented in [`docs/CONFIGURATION.md`](../docs/CONFIGURATION.md).
2. Keep fail-closed: missing secrets, bad URLs, or unknown keys must refuse startup.
3. Add or update a test under `src/test/java` that fails without your change.
4. Do not put policy logic in a source plugin; sources contribute capability only.
5. Update [`docs/guide/modules.html`](../docs/guide/modules.html#aegis-config) if a public key or
   control changes.

## Where to look in the source

| Concern | Package / entry |
| --- | --- |
| Load + validate YAML/JSON, build the gateway | `io.github.vaquarkhan.aegis.config` (`ConfigLoader`, `ConfigNode`, `GatewayFactory`) |
| Declarative HTTP tools | `…config.http` |
| Credentials / token exchange | `…config.credentials` |
| Hooks | `…config.hooks` |
| Secrets | `…config.secrets` |
| OPA | `…config.policy` |
| OpenAPI draft | `…config.openapi` |
| ServiceLoader interfaces | `…config.spi` |

## Further reading

- [Configuration reference](../docs/CONFIGURATION.md): [`secrets`](../docs/CONFIGURATION.md#secrets),
  [`sources[]`](../docs/CONFIGURATION.md#sources), [controls](../docs/CONFIGURATION.md#controls),
  [`serverCatalog`](../docs/CONFIGURATION.md#servercatalog), [`state`](../docs/CONFIGURATION.md#state),
  [`hooks`](../docs/CONFIGURATION.md#hooks-extension-controls)
- [Developer guide](../docs/guide/developer.html)
- [Module reference (HTML)](../docs/guide/modules.html#aegis-config)
- [AGENTS.md](../AGENTS.md): architecture boundaries for AI-assisted and human contributors
