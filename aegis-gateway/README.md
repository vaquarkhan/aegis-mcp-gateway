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
# aegis-gateway

Own-host front door: JDK HTTP(S) and stdio MCP endpoint, shaded runnable jar. Host identity and
route admission come from `aegis-mcp-security`; governance comes from the engine.

## What it does

This is the process an operator runs. It loads one configuration file through `aegis-config`, which
builds the governance engine, the MCP handler and the admin API, and then serves MCP over HTTP(S) on
the JDK's built-in server, or over stdin and stdout when a desktop client launches it with `--stdio`.
Each HTTP request is authenticated and admitted to its route by `aegis-mcp-security` before the body
is read; only then does the shared handler see it and pass tool calls to the engine. The module holds
no governance logic of its own. The same jar also runs offline commands for audit verification,
token hashing, signed catalog manifests, the signed server catalog, break-glass orders and OpenAPI
drafts.

## What it owns

- `Main` entry, `--config`, `--stdio`, `--verify-audit`, `--hash-token`, and the catalog-manifest,
  server-catalog, break-glass and OpenAPI-draft CLIs.
- Listener binding, health/readiness/metrics routes, graceful shutdown.
- Packaging of the `*-all.jar` used by tutorials and the container image.

## How it works end to end

1. **Arguments.** `Main.main` calls `Main.run`, which reads the arguments left to right and collects
   `--config <file>`, `--stdio`, `--verify-audit <file>` and `--checkpoints <file>`. The first offline
   command flag (`--hash-token`, `--manifest-*`, `--server-catalog-*`, `--break-glass-*`,
   `--openapi-draft`) ends parsing: it gets every argument after it and runs without loading a
   configuration. An unknown argument or a flag without its value prints a message and returns 2.
   `main` calls `System.exit` only for a non-zero code.
2. **Offline audit check.** `--verify-audit` must run without `--config` or `--stdio`. It uses
   `AuditChainVerifier`, or `AuditCheckpointVerifier` when `--checkpoints` is given, and prints
   `OK records=... head=...` or `BROKEN ...`.
3. **Configuration.** With `--config`, `Main` first calls `AegisServer.applyServerTimeLimits()`, which
   sets the JDK properties `sun.net.httpserver.maxReqTime` (30 s) and `maxRspTime` (120 s) unless the
   operator already set them, before any JDK HTTP server class loads. It then calls
   `GatewayFactory.build(cfg, BuildContext.defaults(cfgDir).withEnv(env))`; relative paths resolve
   against the configuration file's directory.
4. **Host settings.** `OwnServerSecuritySettings.parse(gateway.hostSection("host"), cfgDir,
   gateway.secrets(), clock)` parses the `host` section strictly, so `tokenEnv`, `hmacSecretEnv` and
   `keystorePasswordEnv` come from the same `secrets` source as the rest of the file. If parsing
   throws, the gateway is closed and the exception propagates.
5. **stdio mode.** `--stdio` needs `host.stdio.actor` (otherwise exit 2). `StdioHost.run` opens
   `gateway.mcp().stdio(identity)` and reads UTF-8 stdin one `\n`-terminated line at a time. Blank
   lines are skipped, every other line goes to `StdioConnection.handleLine`, and a non-null reply is
   written as one line on stdout. A line over 1 MiB gets the JSON-RPC error `-32600` "message too
   large". Every call runs as the fixed `host.stdio` identity (issuer `aegis-stdio`, `authMethod`
   `stdio`, no OAuth scopes); there is no bearer token and no perimeter on stdio. At end of input the
   gateway is closed and `Main` returns 0.
6. **Listener.** Otherwise `new AegisServer(gateway, settings)` binds `listen.host:listen.port`
   (default `127.0.0.1:8080`, backlog 128). With `tls.keystore` it creates an `HttpsServer` from the
   keystore (`keystoreType`, default PKCS12) with the JDK default TLS parameters. With
   `tls.clientAuth: require` the handshake needs a client certificate that chains to the PEM bundle in
   `tls.clientCaFile`, and only that bundle is trusted. Work runs on a fixed pool of `listen.threads`
   (default 32) with a queue of `threads * 8`; when the queue is full, `CallerRunsPolicy` makes the
   accept thread run the task, so accepting slows down instead of queueing without limit. The
   constructor refuses a `host.cors.allowedOrigins` that is not a subset of `server.allowedOrigins`.
7. **Routes.** The constructor registers these contexts:

   | Path | Authentication | Behaviour |
   | --- | --- | --- |
   | `listen.mcpPath` (`/mcp`) | perimeter, route group `MCP` | `gateway.mcp().handleHttp`; any sub-path other than `/` is 404 |
   | `listen.adminPath` (`/aegis/v1`) and below | perimeter, route group `ADMIN` | `gateway.admin().handle` with the sub-path |
   | `host.metrics.path` (`/metrics`) | perimeter, route group `METRICS` | GET only (else 405); Prometheus text from `gateway.metrics()`; registered only when metrics are enabled |
   | RFC 9728 path from `host.oauth.resource` | none | GET only (else 405), exact path (else 404), `Cache-Control: public, max-age=3600` |
   | `/healthz` | none | 200 `{"status":"ok"}` |
   | `/readyz` | none | 200 `ready` with `activeTools` when at least one catalog entry is active, else 503 `no active tools` |

8. **Request path.** For the MCP and admin routes, `AegisServer.route` runs these steps in order:

   ```text
   socket
     -> HttpServer or HttpsServer (TLS handshake; client certificate with tls.clientAuth: require)
     -> worker pool -> context for mcpPath or adminPath -> AegisServer.guarded -> route
          MCP sub-path other than "" or "/"         404 not found
          OPTIONS                                   OwnServerPerimeter.preflight -> 204 or 403
          Origin not allowed (when CORS is on)      403 origin not allowed
          OwnServerPerimeter.admit(route group, headers, verified client chain, empty body)
                                                    401 or 403 with the perimeter's headers
          read body, at most listen.maxRequestBytes 413 request body too large
          SubjectToken.bind(verified JWT)           MCP route and jwt:* callers only
          McpServerHandler.handleHttp or AdminApi.handle, with admission.caller()
            -> GovernanceEngine.call for tools/call
     <- write(): status, handler headers, CORS headers for an allowed origin, nosniff
   ```

   The body is read only after admission, so unauthenticated traffic cannot make the host buffer it.
   For a JWT caller, `AegisServer.verifiedJwt` takes the single `Authorization: Bearer` value and
   binds it to `SubjectToken` for this request only, so a `token-exchange` credential provider can
   use it; the binding is closed in a `finally`. Static-token, SPIFFE and admin requests bind nothing.
9. **Responses.** `write` copies the handler's headers, adds the perimeter's CORS response headers
   for an exact allowed origin, and sets `X-Content-Type-Options: nosniff`. MCP, admin and metrics
   exchanges run inside `AegisServer.guarded`, which always closes the exchange.
10. **Shutdown.** `start()` logs the listening URL and `Main` waits on a latch. The JVM shutdown hook
    (thread `aegis-shutdown`) calls `AegisServer.close()`: `server.stop(1)` (up to one second for
    open exchanges), `pool.shutdownNow()`, then `gateway.close()`.

### Command line

| Command | Arguments | What it does |
| --- | --- | --- |
| `--config` | `<aegis.yaml\|aegis.json>` | Serve HTTP(S) as above |
| `--config ... --stdio` | | Serve MCP on stdin and stdout as `host.stdio` |
| `--config ... --require-server-catalog` | `<review.pub>` (absolute path) | Host trust anchor: refuse a configuration without `serverCatalog`, or one whose `publicKeyFile` is another key. Same as `AEGIS_REQUIRE_SERVER_CATALOG_KEY` or `-Daegis.requireServerCatalogKey` |
| `--policy-test` | `<aegis.yaml> <cases.json>` | Evaluate a declarative `type: http` configuration through steps 1 to 3 with `GovernanceEngine.explain`; audit and state are temporary and source credentials are removed. Remote OPA, MCP and plugin sources are refused before discovery, so no backend call occurs. Prints the deny code, `APPROVAL_REQUIRED` or `ALLOWED` per case; exit 1 when a case does not match |
| `--verify-audit` | `<journal> [--checkpoints <file>]` | Verify the hash chain, and the checkpoints when given; exit 0 intact, 1 broken |
| `--hash-token` | token on stdin | Print the SHA-256 hex of the trimmed token, for `identity.static.tokens[].sha256` |
| `--manifest-keygen` | `<private.pem> <public.pem>` | New Ed25519 key pair; private file `rw-------` where POSIX permissions exist |
| `--manifest-create` | `<catalog.json> <manifest.json> <gateway-name> [--serial <n>]` | Pin every tool name to its digest from a reviewed `GET /aegis/v1/catalog` reply; a serial turns on rollback protection |
| `--manifest-sign` | `<manifest.json> <private.pem>` | Write `<manifest.json>.sig` |
| `--manifest-verify` | `<manifest.json> <public.pem> <gateway-name>` | Check the manifest and its `.sig` as the gateway does at start; prints `OK pins=N` |
| `--server-catalog-create` | `<aegis.yaml> <catalog.json> <servers.json> [prompts.json] [--serial <n>]` | One ACTIVE record per configured source with its configuration digest and tool pins, plus `prompt:<name>` pins from a `GET /aegis/v1/prompts` reply |
| `--server-catalog-verify` | `<servers.json> <public.pem> <gateway-name>` | Check the signed server catalog; prints `OK servers=N active=M` (and `serial=N` when set) |
| `--break-glass-create` | `<order.json> <gateway-name> <incident> <minutes> <id>=QUARANTINED\|REVOKED ... [--serial <n>]` | Containment order valid from now for 1 to 1440 minutes (`BreakGlassContainment.MAX_WINDOW`, 24 hours) |
| `--break-glass-verify` | `<order.json> <public.pem> <gateway-name>` | Check the signed order against the current time |
| `--openapi-draft` | `<openapi.yaml\|json> <draft.yaml> [--source-id <id>] [--base-url <url>]` | Draft configuration with one DESTRUCTIVE tool per operation; parsed as Aegis YAML before it is written; never contacts the API |

Exit codes: 0 success; 1 audit chain broken, a `--policy-test` case that does not match, or
`BROKEN <message>` from an offline command; 2 usage
error, or `refusing to overwrite` an existing file. Key generation, `--manifest-create`,
`--server-catalog-create`, `--break-glass-create` and `--openapi-draft` never overwrite;
`--manifest-sign` replaces an existing `.sig`. Use a separate key pair for break-glass orders.

## Main types

| Type | Role |
| --- | --- |
| `Main` | Argument parsing, mode selection, exit codes, shutdown hook |
| `AegisServer` | JDK HTTP(S) listener, TLS and mutual TLS, worker pool, routes, `guarded` error handling |
| `StdioHost` | Line-delimited JSON-RPC over stdin and stdout with a fixed identity |
| `CatalogManifestCommands` | `--manifest-keygen`, `--manifest-create`, `--manifest-sign`, `--manifest-verify` |
| `ServerCatalogCommands` | `--server-catalog-*` and `--break-glass-*` |
| `PolicyTestCommand` | `--policy-test` |
| `OpenApiDraftCommand` | `--openapi-draft` |

From other modules: `GatewayFactory` and `Gateway` (`aegis-config`), `OwnServerSecuritySettings` and
`OwnServerPerimeter` (`aegis-mcp-security`), `McpServerHandler` (`aegis-mcp`), `GovernanceEngine`
(`aegis-engine`).

## Failure behaviour

- Configuration or `host` errors throw out of `Main.run` before anything listens; unknown `host` keys
  are refused.
- A journal that no longer matches its audit checkpoints stops start-up.
- A CORS origin outside `server.allowedOrigins`, or a `clientCaFile` with no certificate, fails the
  `AegisServer` constructor.
- Without a client certificate that chains to `clientCaFile`, the TLS handshake fails.
- No credential, or one that does not verify: 401 with `WWW-Authenticate`. Outside the route group:
  403. Valid JWT without a required scope: 403 `insufficient_scope`. None of these reach the handler.
- A `Content-Length` above `listen.maxRequestBytes` or not a number, or a streamed body that grows
  past it: 413.
- An unexpected `RuntimeException` is logged and answered with a generic 500 `{"error":"internal
  error"}` when no response has started; exception text never reaches the client.
- `/readyz` answers 503 while no tool is active.
- On stdio, an oversized line gets a JSON-RPC error and the loop continues with the next line.

## Tests

- `GatewayHostTest`: JWT over real HTTP (401 for none, forged and `alg: none` tokens), dry run,
  approval and promotion, a self-approval refused, the caller's token never forwarded; the audit verify
  endpoint group; route groups and CORS before MCP dispatch; 404, 405 and 413 handling and protected
  metrics; the CORS subset check; Derby state; stdio identity and the 1 MiB line limit; RS256 JWKS
  rotation and delegation; authenticated metrics and `traceparent` reaching the backend.
- `GuardedExchangeTest`: the generic 500, and that a response already sent is kept.
- `MainAndSettingsTest`: every non-blocking command-line mode and exit code, audit checkpoints, strict
  `host` parsing, static tokens resolved by digest only.
- `ProtectedResourceMetadataTest`: the RFC 9728 document is served without a token, every 401 points
  at it, a missing scope gets 403 `insufficient_scope`, and nothing is published without `host.oauth`.
- `SpiffeMutualTlsTest`: real mutual TLS with `keytool` certificates; a mapped X.509-SVID gets in,
  other SVIDs get 401, no or untrusted certificates fail the handshake.
- `SignedCatalogManifestFlowTest` and `ServerCatalogFlowTest`: the manifest, server-catalog and
  break-glass commands run through `Main.run` against a restarted gateway.
- `OpenApiDraftCommandTest`: the draft is written once and starts a gateway.
- `TwoGatewayProcessesPostgresIT`: two gateway JVMs sharing PostgreSQL state; only with
  `-Ppostgres-integration` and the `AEGIS_POSTGRES_*` variables.

## Developer instructions

```powershell
mvn -B -pl aegis-gateway -am package
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config path/to/aegis.yaml
```

1. Keep transport and identity concerns out of `aegis-engine`.
2. Do not embed Knox classes in this jar
   ([`KnoxPackagingBoundaryTest`](../examples/aegis-examples/src/test/java/io/github/vaquarkhan/aegis/examples/KnoxPackagingBoundaryTest.java)
   in `examples/aegis-examples` guards that).
3. After listener or CLI changes, update [`docs/CONFIGURATION.md`](../docs/CONFIGURATION.md) and the
   developer guide.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-gateway)
- [Handbook](../docs/guide/handbook.html)
- [AGENTS.md](../AGENTS.md)
