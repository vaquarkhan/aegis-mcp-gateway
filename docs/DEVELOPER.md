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
# Developer guide

This page is for two audiences: people who **use** Aegis to govern their project (sections 1 to 4)
and people who **change** Aegis (sections 5 to 9). The same guide as a web page, with the extension
points table and the test profiles, is [guide/developer.html](guide/developer.html). The fastest hands-on start is the
[Spark and Spark Connect tutorial](TUTORIAL-SPARK-AND-SPARK-CONNECT.md).

## 1. Build and run

```bash
mvn -B clean install                      # all modules, tests, license headers, coverage gate
mvn -B -Preal-engines clean install       # plus real Flink, Spark, Spark Connect (and Iceberg/Kafka if configured)
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config my-aegis.yaml
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config my-aegis.yaml --stdio
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --verify-audit audit/aegis.jsonl
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --verify-audit audit/aegis.jsonl --checkpoints anchor/heads.jsonl
echo -n "$TOKEN" | java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --hash-token
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --openapi-draft openapi.yaml draft.yaml --source-id orders
docker build -t aegis-gateway:2.0.0-SNAPSHOT .        # non-root image; plugin jars go in /opt/aegis/plugins
helm install aegis charts/aegis -f my-values.yaml      # StatefulSet, one audit journal volume per replica
```

JDK 17 or newer, Maven 3.9 or newer. For Spark Connect sources use
`examples/aegis-gateway-spark/target/aegis-gateway-spark-2.0.0-SNAPSHOT-all.jar` (same commands).

## 2. Write a configuration

Start from the closest named shared-profile folder: `adopters/configurations/spark/spark.yaml`,
`adopters/configurations/spark-connect/spark-connect.yaml`,
`adopters/configurations/flink/flink.yaml`, `adopters/configurations/iceberg/iceberg.yaml`,
`adopters/configurations/kafka/kafka.yaml`, `adopters/configurations/a2a/a2a.yaml` (an A2A agent),
`adopters/configurations/existing-mcp-server/existing-mcp-server.yaml`, or the teaching template
`adopters/configurations/template/TEMPLATE.yaml`. The minimum is: `sources`, `policy`,
`audit.file`, and a host section. Every key is in
[docs/CONFIGURATION.md](CONFIGURATION.md); an unknown key stops startup with its path, so typos
cannot silently disable a control.

Checklist for a new source:

1. Classify every tool (`class`). Anything you do not classify is DESTRUCTIVE.
2. Name the resources each tool touches (`resources: [{type, pointer}]`), so policy and scopes can use them.
3. Give destructive tools a real preview (`dryRun`): the backend's validate-only flag (`ARGUMENT`) or a
   read tool (`TOOL`) beats the gateway-only default.
4. Put the backend's host in `egress` if it is not the source URL.
5. Name secrets with the `...Env` keys, and choose where their values come from with `secrets.source`
   (`env`, `file` or `vault`; see [CONFIGURATION.md](CONFIGURATION.md#secrets)).
6. If the backend should see each user rather than one gateway account, use
   `credentials.type: token-exchange` (RFC 8693, own host with JWT identity; see
   [CONFIGURATION.md](CONFIGURATION.md#per-caller-credentials-rfc-8693-token-exchange)).

For a REST API with an OpenAPI 3.x document, `--openapi-draft` writes a starting file in which every
tool is `DESTRUCTIVE` and policy denies everyone; work through the checklist above before use.

## 3. Connect a client

- **Streamable HTTP**: `POST http://host:8080/mcp` with `Authorization: Bearer <token>`. 2026-07-28
  clients send `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` headers matching the body; 2025
  clients call `initialize` and then use the `Mcp-Session-Id` they receive.
- **stdio**: run the jar with `--stdio`; the identity is `host.stdio`.
- **Control values** go in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken`,
  `.../dryRunReceipt`, and optionally a W3C `traceparent`.
- **Decisions** come back in `result._meta["io.github.vaquarkhan.aegis/decision"]` for tools, and as
  JSON-RPC error -32010 with the deny code for resources, prompts and completion.

## 4. Operate

- Approvals: `GET /aegis/v1/approvals`, `POST /aegis/v1/approvals/{id}/approve|deny` (approver groups).
- Catalog: `GET /aegis/v1/catalog`, then `POST /aegis/v1/catalog/{tool}/accept` with exactly one
  `Aegis-Definition-Digest: sha256:...` header copied from the reviewed quarantined entry; prompts
  use the same header at `/aegis/v1/prompts/{prompt}/accept`.
- Health: `/healthz`, `/readyz` (503 until at least one tool is active). Metrics: `/metrics`.
- Audit: ship the JSONL journal; verify with `--verify-audit`.

## 5. Module map and where to change what

| You want to | Change | Do not |
| --- | --- | --- |
| add a control to every call | `aegis-engine` (`GovernanceEngine`, `control/`) or a `GovernanceHook` plugin | put governance in a source or a front door |
| support a new backend with a Java client | a new module with a `ToolSourceFactory` (see `examples/aegis-spark-connect`) | add its client to engine, mcp or config |
| support a REST backend | a config file with `type: http` | write Java |
| change protocol handling | `aegis-mcp` (`McpServerHandler`, `HttpMcpClient`, `StdioMcpClient`) | trust a header that disagrees with the body |
| add a config key | `GatewayFactory` + `CONFIGURATION.md` + a test | accept unknown keys |
| change own-server identity, OAuth metadata, route groups or CORS | `aegis-mcp-security` (`OwnServerSecuritySettings`, `OwnServerPerimeter`, `JwtVerifier`) | put transport code there; `aegis-gateway` owns the JDK HTTP binding |
| add a state provider | a module with a `StateStoreFactory` (see `aegis-state-derby`) | weaken the atomic `StateStore` contract |
| change trace export | `aegis-observability-otlp` | let an exporter block or change a decision |
| change Knox integration | `aegis-knox3-extension` only | bundle Knox, Jersey, Jackson or servlet classes |

## 6. Write a source plugin

```java
public final class MyEngineToolSourceFactory implements ToolSourceFactory {
    @Override public String type() { return "my-engine"; }

    @Override public ToolSource create(String id, ConfigNode s) {
        String host = s.requiredString("host");          // read every key you support;
        int port = (int) s.integer("port", 9000);        // unknown keys fail startup
        return new MyEngineToolSource(id, host, port);   // discover() returns descriptors + executors
    }
}
```

Register it in `META-INF/services/io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory`. Rules:

- Contribute capability only: descriptors and executors. Classification, resources, groups and
  previews come from the operator's `tools:` section.
- `ToolExecutor.egressTargets` must return the backend endpoint (as `http(s)://host:port` for
  non-HTTP protocols), so the egress guard sees it.
- Use `ExecutionContext.credentials()` (the gateway's service credential) and forward
  `ExecutionContext.traceparent()` if the protocol carries metadata.
- Honour `ExecutionContext.timeoutMillis()`; a timeout is `ExecutionException(..., true, ...)`.
- Caller mistakes (unknown table, bad SQL) are `ToolResult` with `isError: true`; outages are
  `ExecutionException` (they count towards the circuit breaker).
- Never build a query by concatenating an argument; validate identifiers and quote them
  (`SparkConnectToolSource.quote`).
- Return `true` from `dispatchesByUpstreamName()` only if one executor serves several tools by
  `ctx.upstreamToolName()`; that is what allows dry-run mode `TOOL` on your source.

## 7. Write a governance hook

If the control can live in another process, you do not need Java at all: configure
`hooks: [{type: remote, settings: {url: ...}}]` and answer `{"allow": true|false}` over HTTP. See
[CONFIGURATION.md](CONFIGURATION.md#hooks-extension-controls). Three reference services in Python's
standard library (PII guard, secrets guard, change window), with unit tests and a live run against the
gateway jar, are in [adopters/hooks](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/adopters/hooks/README.md).

For an in-process control, implement `GovernanceHook`, ship a `GovernanceHookFactory`, and enable it
with `hooks: [{type: my-hook, settings: {...}}]`. `aegis-mcp-guardrails` is a small worked example
(`ProjectGovernanceGuardrail` and its factory):

- `before(Context)` may deny with `POLICY_DENIED`, `INVALID_INPUT` or `PROMPT_INJECTION`.
- `afterTool(Context, ToolResult)` may rewrite or withhold a tool result.
- `afterContent(Context, Map)` may rewrite or withhold a resource, prompt or completion result.
- `throw new HookDenied(code, reason)` to withhold with an explanation; the reason reaches audit.
- `close()` releases anything the hook holds; the engine calls it on shutdown.

One hook sees all four operations by default (`Context.operation()` tells you which). Operators
narrow that with `operations`, `methods`, `groups` and `sources`, and choose `ENFORCE` or `AUDIT`.
Write the hook as if it were always enforcing: the runner handles the mode. In `ENFORCE` a hook that
throws denies; in `AUDIT` the same event is only recorded. Do not mutate the `Context`, and do not
rely on being called for every request.

## 8. Tests

- JUnit 5; every behaviour change comes with a test that fails without it.
- Protocol tests run against `TestMcpServer` / `TestStdioMcpServer` (`aegis-mcp` test jar). They
  are independent of Aegis's production handler but reuse its strict JSON utility; they prove
  deterministic protocol and failure behavior, not external-server compatibility.
- No external network in the default build; the Kafka Admin test starts a real Kafka 3.9.1 broker on
  loopback. The other real engines live behind `-Preal-engines`
  (`examples/aegis-validation`, `RealSparkConnectServerTest`); tests that need an external server skip
  (never pass) when it is not configured.
- Coverage: JaCoCo per module with measured floors (`coverage.line.minimum`, `coverage.branch.minimum`);
  the build fails if a module drops below its floor.
- Outside Maven: `adopters/hooks` (`python -m unittest test_hooks`, `python e2e_gateway.py --jar ...`)
  and `charts/aegis/ci` (helm lint, kubeconform, `start_rendered.py`). CI runs both and also builds and
  starts the container image.

## 9. Conventions (from AGENTS.md)

- Fail closed. If a control cannot decide, deny.
- Reuse the stable deny codes; new ones need maintainer review.
- Apache License 2.0 header on every file that can carry one (RAT runs in the build).
- Keep the engine, mcp and config modules free of third-party runtime dependencies.
- Humans commit; agents propose. Disclose AI assistance in the PR.
