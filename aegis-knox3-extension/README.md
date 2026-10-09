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
# aegis-knox3-extension

Optional Apache Knox 3.0.0 front door. Knox authenticates, asserts identity, and admits the route;
Aegis receives the Knox `Subject`, parses the MCP body, and applies the same governance engine as
the own host. Knox APIs stay in `provided` scope, so no Knox class is packaged in this module's jars.

## What it does

Aegis is an independent MCP governance gateway with its own host (`aegis-gateway`). Teams that
already run Apache Knox can instead host Aegis inside a Knox topology with this module.

- Knox does what it already does: TLS, authentication, identity assertion and route authorization
  (for example `AclsAuthz`). It decides who may reach the Aegis route at all.
- Aegis governs what the admitted caller asks for. It reads the MCP JSON-RPC body and runs the
  per-tool, per-argument engine chain before any backend is called.

Aegis is a Knox service, not a Knox fork: it plugs in through Knox's public service deployment API,
nothing in Knox is patched, and the jars go into Knox `ext/`.

## How it works end to end

1. **Discovery.** At startup Knox finds `AegisKnoxServiceContributor` through
   `META-INF/services/org.apache.knox.gateway.deploy.ServiceDeploymentContributor`. It extends Knox's
   `JerseyServiceDeploymentContributorBase` (the native JAX-RS Service API pattern) with role
   `AEGIS_MCP`, name `aegis-mcp`, JAX-RS package `io.github.vaquarkhan.aegis.knox.rs` and the single
   URL pattern `aegis-mcp/v1/**?**`. It also registers `AegisKnoxLifecycle` as a servlet listener.
2. **Topology.** The operator adds the service with an `aegis.config` parameter naming the Aegis
   configuration file (full example in [KNOX-EXTENSION.md](../docs/KNOX-EXTENSION.md#install)):

   ```xml
   <service>
     <role>AEGIS_MCP</role>
     <param><name>aegis.config</name><value>/etc/knox/conf/aegis/aegis.json</value></param>
   </service>
   ```
3. **Gateway build.** Nothing is built at deployment. On the first request, `KnoxGatewayRegistry.get`
   reads `aegis.config` from the servlet context and calls
   `GatewayFactory.build(path, BuildContext.defaults(parent))`. The result is cached per topology web
   application (keyed by its `ServletContext`), so the gateway is built once and reused. A blank
   parameter or any build error leaves only the Aegis route answering 503, never ungoverned access.
   A failed build is tried again on a later request once `KnoxGatewayRegistry.RETRY_AFTER` (10 seconds)
   has passed, so a corrected configuration file takes effect without redeploying the topology.
4. **Knox providers.** A request to `https://<knox>/gateway/<topology>/aegis-mcp/v1/mcp` first passes
   the topology's provider chain: authentication, identity assertion, then authorization such as
   `AclsAuthz` with an `AEGIS_MCP.acl` rule. `AclsAuthz` decides on user, group and IP only and never
   reads the body; `KnoxAclRouteScopeTest` checks this.
5. **JAX-RS resource.** `AegisMcpResource` (`@Path("aegis-mcp/v1")`) takes POST, GET and DELETE on
   `mcp`. It copies the headers, reads the body up to 1 MiB and builds a host-neutral `McpHttpRequest`.
6. **Identity.** `KnoxIdentityBridge.fromCurrentSubject` reads the current `Subject` and builds a
   `CallerIdentity`. The topology name is the last segment of the servlet context path.

   | `CallerIdentity` field | Taken from |
   | --- | --- |
   | `actor` | `PrimaryPrincipal` name |
   | `effectiveSubject` | `SubjectUtils.getEffectivePrincipalName`: the `ImpersonatedPrincipal`, else the primary |
   | `groups` | every `GroupPrincipal` name |
   | `actorChain` | `sub` of each entry of `ActorChainPrincipal.getActorChain()` |
   | `issuer`, `authMethod` | `knox:<topology>` and `knox` |
   | `scopes` | always empty: Knox establishes no OAuth grants |

   With no subject or a blank primary principal the bridge returns null and the handler answers 401.
   Aegis never sees the caller's credential.
7. **MCP handler and engine.** The resource calls `gateway.mcp().handleHttp(request, caller)`.
   `McpServerHandler` validates the MCP and JSON-RPC request and hands tool calls to
   `GovernanceEngine`, which allows or denies before the source executor runs. A backend receives the
   source's configured service credential, never the Knox caller's credential.
8. **Admin, health and metrics.** `admin/{path}` (GET and POST) goes to `gateway.admin().handle`
   (`AdminApi`) with path `/{path}`, so `.../aegis-mcp/v1/admin/approvals` is admin path `/approvals`.
   An admin body is read within the same bound and discarded. `health` answers 200 or 503. `metrics`
   serves Prometheus text behind the same provider chain.
9. **Undeploy.** When Knox undeploys or redeploys the topology, `AegisKnoxLifecycle.contextDestroyed`
   calls `KnoxGatewayRegistry.close`, which removes the cached entry and closes the gateway (audit
   journal, stdio children, refresh thread).

**What `host:` does not do under Knox.** `GatewayFactory` sets the top-level `host` and `knox`
sections aside (`HOST_SECTIONS`) without building anything from them, and this module never reads
them. The own-host listener, TLS, JWT validation and OAuth route scopes in `host:` have no effect
here; the Knox topology owns those concerns.

```text
MCP client
  | HTTPS /gateway/<topology>/aegis-mcp/v1/mcp
  v
Knox topology provider chain
  authentication       (for example ShiroProvider, KnoxSSO, JWT federation)
  identity assertion   (PrimaryPrincipal, ImpersonatedPrincipal, GroupPrincipal)
  authorization        (for example AclsAuthz: user, group, IP; body not read)
  | admitted; Subject established
  v
AegisMcpResource  (JAX-RS, aegis-mcp/v1)
  KnoxGatewayRegistry.get(ServletContext, aegis.config)   -> 503 when unavailable
  read body, bounded to 1 MiB                              -> 413 when larger
  KnoxIdentityBridge: Subject -> CallerIdentity           -> 401 when no subject
  |
  v
Gateway  (built once per topology by GatewayFactory)
  McpServerHandler -> GovernanceEngine -> source executor -> backend
  (backend gets the source service credential, never the Knox credential)
```

## Packaging

Install either shape into Knox `ext/` and restart Knox (details in
[KNOX-EXTENSION.md](../docs/KNOX-EXTENSION.md#install)):

- **Five jars:** `aegis-engine`, `aegis-mcp`, `aegis-observability-otlp`, `aegis-config` and
  `aegis-knox3-extension`. Keep `aegis-observability-otlp` even with tracing off, because
  `aegis-config` links against it. PostgreSQL shared state also needs `aegis-state-postgres` and the
  PostgreSQL JDBC driver.
- **One jar:** `target/aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar`, attached by
  `maven-shade-plugin` with classifier `knox-bundle`. Its include list is exactly `aegis-engine`,
  `aegis-mcp`, `aegis-observability-otlp`, `aegis-config`, `aegis-state-postgres`,
  `org.postgresql:postgresql` and `aegis-knox3-extension`. `ServicesResourceTransformer` merges the
  `META-INF/services` files.

`provided` scope (supplied by Knox at runtime, never packaged): Knox `gateway-spi`,
`gateway-provider-jersey` and `gateway-provider-security-authz-acls` at `${knox.version}` (3.0.0),
`javax.ws.rs-api` 2.0 and `javax.servlet-api` 4.0.1. Jersey, Jackson and SnakeYAML are left out by the
include list, so Knox's own copies are used.

[`KnoxPackagingBoundaryTest`](../examples/aegis-examples/src/test/java/io/github/vaquarkhan/aegis/examples/KnoxPackagingBoundaryTest.java)
(in `examples/aegis-examples`) enforces the boundary:

- no `src/main` Java file outside this module references `org.apache.knox`;
- every `org.apache.knox` dependency in this `pom.xml` is `provided`, and `aegis-gateway/pom.xml` does
  not reference this module;
- in the built jars, both hosts contain `GovernanceEngine`; the bundle contains
  `AegisKnoxServiceContributor`, `McpServerHandler`, `GatewayFactory`, `OtlpSpanExporter`,
  `PostgresStateStoreFactory` and `org/postgresql/Driver`, and contains no `org/apache/knox/`,
  `javax/servlet/`, `javax/ws/rs/`, `jakarta/`, Jersey, Jackson or SnakeYAML entries, nor the own-host
  `AegisServer` and `OwnServerPerimeter`; the own-host jar contains no Knox classes. This check opens
  both built jars, so it needs a reactor build that packages them (for example `mvn -B clean install`).

## Main types

| Type | Module | Role |
| --- | --- | --- |
| `AegisKnoxServiceContributor` | this module | Knox service contributor: role, name, JAX-RS package, URL pattern, listener |
| `AegisKnoxLifecycle` | this module | `ServletContextListener`; closes the topology's gateway on undeploy |
| `KnoxGatewayRegistry` | this module | one cached `Gateway` (or unavailable reason) per `ServletContext` |
| `KnoxIdentityBridge` | this module | Knox `Subject` to `CallerIdentity` |
| `rs.AegisMcpResource` | this module | JAX-RS endpoints `mcp`, `admin/{path}`, `health`, `metrics` |
| `Gateway`, `GatewayFactory`, `BuildContext` | `aegis-config` | builds and holds the engine, MCP handler and admin API |
| `McpServerHandler`, `AdminApi`, `McpHttpRequest`, `McpHttpResponse` | `aegis-mcp` | host-neutral MCP and admin HTTP handling |
| `GovernanceEngine`, `CallerIdentity` | `aegis-engine` | governance chain and caller model |

## Failure behaviour

| Situation | Result |
| --- | --- |
| `aegis.config` blank or missing, or the gateway fails to build | `mcp` and `admin` answer 503; `health` answers 503 with reason `Aegis configuration is unavailable`; `metrics` answers 404. The error is logged; responses never contain the path. Other topology services keep working. |
| The configuration file is corrected | The first request after the 10-second retry interval builds the corrected configuration; no redeploy is needed (`KnoxExtensionTest.aCorrectedConfigurationTakesEffectWithoutRedeployingTheTopology`). |
| Knox route authorization refuses the caller | Knox answers (403 from `AclsAuthz` in `KnoxAclRouteScopeTest`); Aegis does not run. |
| No authenticated `Subject` reaches Aegis | `mcp`, `admin` and `metrics` answer 401; there is no anonymous path. |
| Request body over 1 MiB | 413 on `mcp` and `admin`. |
| Engine denies the call | The response carries a stable deny code from [DENY-CODES.md](../docs/DENY-CODES.md), for example `READONLY_CALLER`; the backend is not called. |

## Limits

- **Knox 3.0.0, javax only.** The module compiles against `javax.servlet` 4.0.1 and `javax.ws.rs`
  2.0. Knox 3.1 moves to the Jakarta namespace, where this build does not load; see
  [Knox 3.1 and Jakarta](../docs/KNOX-EXTENSION.md#knox-31-and-the-jakarta-namespace) and
  [ADR-002](../docs/adr/ADR-002-KNOX-3.1-JAKARTA-TARGET.md).
- **`actionScopes` are denied.** `KnoxIdentityBridge` builds `CallerIdentity` with empty `scopes`, and
  `GovernanceEngine` denies a tool whose `actionScopes` the caller lacks with `SCOPE_DENIED`.
- **No token exchange.** A `credentials.type: token-exchange` source needs a verified caller token
  bound through `SubjectToken`, which only the own host (`AegisServer`) binds. Under Knox no token is
  bound, so `TokenExchangeCredentials` fails closed and the call is denied with `BACKEND_ERROR`. Use
  source service credentials.
- **Approvers must be non-delegated.** `ApprovalService` refuses an approval when the effective
  subject differs from the actor (an `ImpersonatedPrincipal`) or an actor chain is present. Give
  approvers a topology path without impersonation.
- **Actor chain reading.** Taking `sub` from each `getActorChain()` entry is this project's reading
  of the 3.0.0 interface; it is an open
  [integration question](../docs/KNOX-EXTENSION.md#integration-questions).
- **Separate audit streams.** Aegis decisions go to the Aegis journal; Knox keeps its own audit log.

## Tests

- `KnoxExtensionTest`: a corrected configuration picked up without redeploy, and a built gateway reused
  across requests; Subject mapping (primary, impersonated, groups, actor chain, issuer; null for
  no principal), contributor role and `META-INF/services` entry, a governed read through the
  resource against a test MCP server, a destructive call denied with `READONLY_CALLER`, 401 without
  a subject, metrics, admin routes and health, and the 503 bad-config path with no path leak.
- `KnoxAclRouteScopeTest`: runs the shipped Knox 3.0.0 `AclsAuthorizationFilter` in front of the
  resource. The ACL admits `list_jobs` and `delete_cluster` alike without reading the body, refuses
  a user outside the ACL with 403, Aegis denies the destructive call, and the backend only ever sees
  the source's `ENGINE_TOKEN` bearer credential (set by Surefire in this `pom.xml`).
- `KnoxPackagingBoundaryTest`: the packaging boundary above.
- Stock Knox 3.0.0: with `-Preal-engines` and `AEGIS_KNOX_HOME` set, `examples/aegis-validation`
  installs the `knox-bundle` jar into an unpacked Knox and drives MCP calls through it.

## Developer instructions

```powershell
mvn -B -pl aegis-knox3-extension -am package
```

1. Install jars into Knox `ext/` as documented in [`docs/KNOX-EXTENSION.md`](../docs/KNOX-EXTENSION.md#install).
2. Do not add Knox types to `aegis-gateway` or `aegis-engine`.
3. Front-door scenarios live under `examples/adopters/` with `AEGIS_KNOX_HOME` set.

## Further reading

- [Knox extension guide](../docs/KNOX-EXTENSION.md)
- [Knox and Aegis layers](../docs/KNOX-AND-AEGIS-MODULE-COMPARISON.md)
- [Module reference](../docs/guide/modules.html#aegis-knox3-extension)
- [AGENTS.md](../AGENTS.md)
