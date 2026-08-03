# Aegis-mcp-gateway
Aegis mcp gateway is centralized security hardened model context protocol  (MCP) gateway that  epose the control and observablity planes of XYZ data system to AI agents through one governed endpoint with pluggable per-engine adaptors.


Cursor build instructions: Aegis MCP Governance Gateway
Instructions for Cursor (or any code-gen agent) to scaffold the multi-module gateway from the design docs in this folder. Goal: a compiling, tested Java multi-module Maven project with a gateway core, a Flink adapter (reusing the validated flink-mcp-server), and stubs plus tests for Kafka, Spark, and Iceberg adapters. Follow every rule. No em dashes in any generated doc. Read DESIGN-apache-mcp-gateway.md and LLD-apache-mcp-gateway.md first; they are the source of truth for behavior. This file says how to lay out and build it.

0. Golden rules (violating any breaks the build)
All Java sources are UTF-8 without a byte order mark. A BOM makes javac fail with "illegal character". On Windows PowerShell do not use Set-Content -Encoding utf8 (it adds a BOM); use a no-BOM writer.
stdout is reserved for MCP JSON-RPC. All logging goes to stderr via Logback. Never add System.out.println.
The MCP JSON mapper is created with new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier().get(). Do not pass a Jackson ObjectMapper to any transport constructor.
Tool input schemas are JSON strings passed via Tool.builder().inputSchema(McpJsonMapper, String).
Secure-by-default: mutate and destructive tools register only when writes are unlocked (master switch + approval secret + allow-list). Read profile is the default.
Identifiers from tool arguments (jobId, jarId, topic, namespace, table, parallelism) are validated before entering any backend path. Hand-built JSON bodies are escaped.
The gateway core has no engine dependencies. Engine clients live only in adapters.
Do not invent SEP or KIP numbers or MCP SDK APIs. Verify SDK API against the jars.
1. Coordinates and toolchain
Parent groupId io.github.vaquarkhan.aegis, version 0.1.0, packaging pom.
Java: maven.compiler.release = 17. Build with JDK 17 or 21, Maven 3.9+.
License: Apache-2.0 (LICENSE, NOTICE, and a header on every source file).
Build: mvn clean verify. Release artifacts via a release profile.
2. Module layout (create exactly this)
aegis-mcp-gateway/
  pom.xml                          (parent, packaging pom, dependencyManagement)
  LICENSE  NOTICE  README.md  .gitignore
  mcp-gateway-core/
    pom.xml
    src/main/java/io/github/vaquarkhan/aegis/core/
      transport/   StreamableHttpTransport.java StdioBoot.java TlsSettings.java OpsServlet.java
      auth/        OAuthResourceFilter.java CimdVerifier.java SpiffeMtls.java
                   TokenRegistry.java BearerAuthFilter.java CallerIdentity.java CallerContext.java
      authz/       PolicyDecisionPoint.java BuiltinPolicyEngine.java
      interceptor/ Interceptor.java Mutator.java Validator.java Observer.java
                   InterceptorChain.java Phase.java Severity.java Decision.java
      governance/  Exposure.java Scope.java Approval.java RateLimiter.java CircuitBreaker.java
                   TimeoutExecutor.java EgressGuard.java PromptInjectionGuard.java OutputControls.java
      integrity/   ToolCatalogIntegrity.java DigestRegistry.java VrpValidator.java
      router/      ToolManifestAggregator.java TaxonomyRouter.java
      observability/ Trace.java AuditLog.java Metrics.java
      finops/      TokenBudget.java SemanticCache.java
      spi/         EngineAdapter.java ToolDef.java ResourceDef.java ReadOnlyGuard.java
                   CredentialResolver.java CallContext.java OutboundCredential.java ToolClass.java
      config/      GatewayConfig.java AdapterRegistry.java
      boot/        GatewayBootstrap.java
    src/main/resources/logback.xml
    src/test/java/io/github/vaquarkhan/aegis/core/  (unit + property tests)
  mcp-adapter-flink/    pom.xml  (depends on core; reuse flink-mcp-server internals)
  mcp-adapter-kafka/    pom.xml
  mcp-adapter-spark/    pom.xml
  mcp-adapter-iceberg/  pom.xml
  mcp-gateway-dist/     pom.xml  (runnable assembly, Docker, Helm, SBOM, signing)
3. Dependencies (pin exact versions in parent dependencyManagement)
io.modelcontextprotocol.sdk:mcp:1.1.3 (compile). Gate any move to 2.0.0; it is a breaking change and must not be auto-adopted.
org.eclipse.jetty.ee10:jetty-ee10-servlet:12.0.16 (compile, HTTP transport and TLS).
org.slf4j:slf4j-api:2.0.16 (compile), ch.qos.logback:logback-classic:1.5.12 (runtime).
org.junit.jupiter:junit-jupiter:5.10.2 (test).
Adapter-only: Flink adapter uses the JDK HTTP client (no Flink core dep); Kafka adapter uses org.apache.kafka:kafka-clients; Iceberg adapter uses the Iceberg REST client or JDK HTTP; Spark adapter uses JDK HTTP for Livy and History plus the Spark Connect client where enabled. Keep engine deps out of the core module.
Plugins: compiler 3.13.0, surefire 3.2.5, jacoco 0.8.12, shade 3.5.1 (in dist); release profile adds source, javadoc, gpg, and the CycloneDX SBOM plugin.
4. Core SPI (generate exactly these contracts)
public enum ToolClass { READ, MUTATE, DESTRUCTIVE }

public record ToolDef(String name, ToolClass cls, String description,
                      String inputSchemaJson,
                      java.util.function.Function<CallContext,String> backend) {}

public record ResourceDef(String uri, String name, String mimeType,
                          java.util.function.Function<CallContext,String> read, boolean redact) {}

public interface ReadOnlyGuard { boolean isReadOnly(String statement); }

public interface CredentialResolver {
    java.util.Optional<OutboundCredential> resolve(CallerIdentity caller, String resource);
}

public interface EngineAdapter {
    String engineId();
    String taxonomyClass();
    java.util.List<ToolDef> tools(GatewayConfig cfg);
    java.util.List<ResourceDef> resources(GatewayConfig cfg);
    default java.util.Optional<ReadOnlyGuard> readOnlyGuard() { return java.util.Optional.empty(); }
    default java.util.Optional<CredentialResolver> credentialResolver() { return java.util.Optional.empty(); }
    java.util.Set<String> egressAllowHosts(GatewayConfig cfg);
}

public record CallContext(String toolName, ToolClass cls, java.util.Map<String,Object> arguments,
                          CallerIdentity caller, String traceId,
                          java.util.Optional<OutboundCredential> outboundCredential) {}
Invariant: adapters may return all their tools, but the core filters registration by toolsAllowed and write-unlock. Adapters never decide allow or deny.

5. Interceptor chain and governance (behavior spec)
Implement the chain per LLD-apache-mcp-gateway.md sections 4 and 7 and the DESIGN doc section 7. Ordered inbound validators, first-denial-wins, with these codes: NOT_EXPOSED(1), READONLY_CALLER(2), SCOPE_DENIED(2), POLICY_DENIED(3), APPROVAL_REQUIRED(4), EGRESS_DENIED(5), RATE_LIMITED(6), BREAKER_OPEN(7), PROMPT_INJECTION(8), VRP_FAILED(9), then EXECUTE(10) distinguishing INVALID_INPUT (no breaker trip) from TIMEOUT and BACKEND_ERROR (trip). Outbound: output bound then redaction. Observers: OTel span, Prometheus metric, hash-chained audit. Validators run on the request thread; backend runs on a bounded per-adapter ThreadPoolExecutor (core 4, max 32, queue 128, daemon, abort) with future.get(toolTimeoutMillis).

6. Reuse the Flink reference implementation
The mcp-adapter-flink module reuses the validated flink-mcp-server internals. Map its existing classes to the SPI: its tool handlers become ToolDef entries, its FlinkRestClient and SqlGatewayClient become the adapter's backends, its SqlReadonlyGuard becomes the ReadOnlyGuard, and its Config keys map into GatewayConfig. Its 48 tests move with it (or are adapted). Flink tools and classes:

READ: list_jobs, get_job, get_job_status, get_job_exceptions, get_job_metrics, list_checkpoints, list_jars, run_sql_readonly, get_cluster_info, list_taskmanagers, get_job_config, get_flink_config.
MUTATE: trigger_savepoint, rescale_job, upload_jar. DESTRUCTIVE: run_jar, stop_job, cancel_job, run_sql_ddl_dml. taxonomyClass: streaming.
7. Other adapters (scaffold with tests, mark backends TODO where a live engine is needed)
Kafka (messaging): describe_topic, list_topics, query_schema_registry, inspect_dlq (READ); create_topic, alter_config (MUTATE); reset_offsets, delete_records (DESTRUCTIVE).
Spark (batch): History reads; Spark Connect or Thrift guarded SQL; Livy submit_batch and kill_application (DESTRUCTIVE).
Iceberg (lakehouse): list and get reads; create and alter (MUTATE); drop, expire_snapshots, remove_orphan_files, rewrite_data_files (DESTRUCTIVE, VRP-gated, with a read-only dry-run companion). Never return vended storage credentials; redact them. Do not fake a live engine in tests; use embedded HTTP fakes (as the Flink client test does with com.sun.net.httpserver) so tests are deterministic and offline.
8. Configuration (GatewayConfig.fromEnv, fail-fast validate)
Engine-prefixed keys as in the DESIGN doc section 15 and the LLD section 21: MCP_GW_TRANSPORT, _HTTP_HOST, _HTTP_PORT, HTTP_TLS, _AUTH_MODE (oauth|cimd|spiffe| tokenfile), _OAUTH_ISSUER/_AUDIENCE/_JWKS_URL, _PDP (builtin|cedar|opa), _WRITE_ENABLED, _APPROVAL_SECRET, _APPROVAL_TTL_MS, _RPS, BREAKER, _TOOL_TIMEOUT_MS, _MAX_BYTES, _MAX_SQL_CHARS, _EGRESS_ALLOW_HOSTS, _TOKEN_BUDGET_DAILY, _ADAPTERS. Per-adapter endpoints stay adapter-scoped (FLINK_REST_URL, SPARK_LIVY_URL, ICEBERG_REST_CATALOG_URL, and so on). validate() rejects bad URLs, unknown transport or auth mode, writes without a secret, http without auth, TLS without a keystore, and out-of-range limits.

9. Tests (must pass)
Reuse and extend the Flink server's 48 tests in the Flink adapter and core.
Core unit and property tests per control: approval token round trip, replay, expiry, wrong scope; SQL guard; output redaction (secrets, PII, credentials); rate limit; circuit breaker; config validate rejects bad input; identifier validation; SSRF egress denies metadata IP; bearer and multi-caller auth; tool-catalog integrity refuses startup on digest drift.
Adapter tests use embedded HTTP fakes. Expected: mvn clean verify prints BUILD SUCCESS with all tests green.
10. Build, run, verify
mvn clean verify                       # compiles, runs all tests, coverage
# run the gateway (stdio, read-only default, Flink adapter)
MCP_GW_ADAPTERS=flink FLINK_REST_URL=http://localhost:8081 \
  java -jar mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
# run authenticated HTTP with TLS and writes unlocked
MCP_GW_TRANSPORT=http MCP_GW_AUTH_MODE=oauth MCP_GW_HTTP_TLS_ENABLED=true \
  MCP_GW_WRITE_ENABLED=true MCP_GW_APPROVAL_SECRET=... \
  java -jar mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
11. Windows note
If generating on Windows with PowerShell, do not write source with Set-Content -Encoding utf8 (adds a BOM). Use a no-BOM UTF-8 writer or configure the editor to save without a BOM. Verify em-dash count is zero in any generated Markdown.

12. What not to do
Do not put engine client libraries in the core module.
Do not register write tools by default; keep secure-by-default.
Do not pass a caller's inbound token to a backend; map outbound credentials instead.
Do not invent SEP or KIP numbers or SDK APIs; if unsure, leave a TODO and cite the design doc section.
Do not use the word Apache or the feather in the project or repo name; the codename is Aegis until any ASF acceptance.
