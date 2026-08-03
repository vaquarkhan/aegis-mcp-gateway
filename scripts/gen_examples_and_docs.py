#!/usr/bin/env python3
"""Create examples/ and product docs (UTF-8 no BOM)."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def w(rel: str, content: str) -> None:
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    if not content.endswith("\n"):
        content += "\n"
    p.write_text(content, encoding="utf-8", newline="\n")
    print("wrote", rel)


def main() -> None:
    w(
        "examples/README.md",
        """# Aegis MCP Gateway examples

Author: Viquar Khan. Apache License 2.0.

Runnable recipes for local development. Prefer read-only profiles unless you intentionally unlock writes.

| Example | Purpose |
| --- | --- |
| [01_stdio_flink_readonly](01_stdio_flink_readonly) | Stdio MCP + Flink read tools |
| [02_http_tokenfile](02_http_tokenfile) | HTTP + bearer token file |
| [03_write_unlock_approval](03_write_unlock_approval) | Mint approval token and call a mutate tool |
| [04_multi_adapter](04_multi_adapter) | Enable flink, kafka, spark, iceberg together |
| [05_cursor_mcp_config](05_cursor_mcp_config) | Cursor IDE MCP server snippet |
| [docker-compose](docker-compose) | Gateway container beside your engines |

Build the dist jar first:

```bash
mvn -q -pl mcp-gateway-dist -am package -DskipTests
```
""",
    )

    w(
        "examples/01_stdio_flink_readonly/README.md",
        """# Stdio + Flink (read-only)

Starts the gateway on stdio with only the Flink adapter. Writes stay locked.

## Prerequisites

- JDK 17+
- Built shade jar
- Flink REST at `FLINK_REST_URL` (default `http://localhost:8081`)

## Run

```bash
export MCP_GW_TRANSPORT=stdio
export MCP_GW_ADAPTERS=flink
export FLINK_REST_URL=http://localhost:8081
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

Windows PowerShell:

```powershell
$env:MCP_GW_TRANSPORT='stdio'
$env:MCP_GW_ADAPTERS='flink'
$env:FLINK_REST_URL='http://localhost:8081'
java -jar ..\\..\\mcp-gateway-dist\\target\\aegis-mcp-gateway-0.1.0-all.jar
```

Mutate tools such as `stop_job` must not appear while writes are locked.
""",
    )
    w(
        "examples/01_stdio_flink_readonly/env.example",
        """MCP_GW_TRANSPORT=stdio
MCP_GW_ADAPTERS=flink
FLINK_REST_URL=http://localhost:8081
MCP_GW_LOG_LEVEL=INFO
""",
    )

    w(
        "examples/02_http_tokenfile/README.md",
        """# HTTP + token file

## Tokens file format (LLD)

```text
callerId:sha256Hex(token):jobsCsv:jarsCsv:readonly[:outboundAuth]
```

Generate a hash:

```bash
echo -n 'dev-secret-token' | sha256sum
```

## Run HTTP (loopback)

```bash
export MCP_GW_TRANSPORT=http
export MCP_GW_HTTP_HOST=127.0.0.1
export MCP_GW_HTTP_PORT=8090
export MCP_GW_AUTH_MODE=tokenfile
export MCP_GW_AUTH_TOKENS_FILE=./auth-tokens.txt
export MCP_GW_ADAPTERS=flink
export FLINK_REST_URL=http://localhost:8081
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

## Call MCP

```bash
curl -sS -H 'Authorization: Bearer dev-secret-token' \\
  -H 'Content-Type: application/json' \\
  -H 'Accept: application/json, text/event-stream' \\
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \\
  http://127.0.0.1:8090/mcp
```

Ops (no auth): `/healthz`, `/readyz`, `/metrics`.

For non-loopback binds enable TLS.
""",
    )
    w(
        "examples/02_http_tokenfile/env.example",
        """MCP_GW_TRANSPORT=http
MCP_GW_HTTP_HOST=127.0.0.1
MCP_GW_HTTP_PORT=8090
MCP_GW_AUTH_MODE=tokenfile
MCP_GW_AUTH_TOKENS_FILE=./auth-tokens.txt
MCP_GW_ADAPTERS=flink
FLINK_REST_URL=http://localhost:8081
""",
    )

    w(
        "examples/03_write_unlock_approval/README.md",
        """# Unlock writes + approval token

```bash
export MCP_GW_WRITE_ENABLED=true
export MCP_GW_APPROVAL_SECRET=$(openssl rand -hex 32)
export MCP_GW_ADAPTERS=flink
export FLINK_REST_URL=http://localhost:8081
```

Mint a token for `stop_job` scoped to a job id:

```bash
java -cp ../../mcp-gateway-core/target/classes \\
  io.github.vaquarkhan.aegis.core.governance.Approval \\
  \"$MCP_GW_APPROVAL_SECRET\" stop_job job-42 300
```

Pass the printed value as `approvalToken` with `jobId=job-42`. Replay must fail with `APPROVAL_REQUIRED`.
""",
    )
    w(
        "examples/03_write_unlock_approval/env.example",
        """MCP_GW_TRANSPORT=stdio
MCP_GW_ADAPTERS=flink
MCP_GW_WRITE_ENABLED=true
MCP_GW_APPROVAL_SECRET=replace-with-long-random-secret
FLINK_REST_URL=http://localhost:8081
""",
    )

    w(
        "examples/04_multi_adapter/README.md",
        """# Multi-adapter gateway

```bash
export MCP_GW_ADAPTERS=flink,kafka,spark,iceberg
export FLINK_REST_URL=http://localhost:8081
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export SCHEMA_REGISTRY_URL=http://localhost:8081
export SPARK_HISTORY_URL=http://localhost:18080
export SPARK_LIVY_URL=http://localhost:8998
export ICEBERG_REST_CATALOG_URL=http://localhost:8181
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

Taxonomy: Flink `streaming`, Kafka `messaging`, Spark `batch`, Iceberg `lakehouse`.

Optional file baseline: set `MCP_GW_CONFIG=./gateway.yaml`.
""",
    )
    w(
        "examples/04_multi_adapter/gateway.yaml",
        """transport: stdio
writeEnabled: false
rps: 10
toolTimeoutMillis: 30000
maxBytes: 65536
egressAllowHosts: localhost,127.0.0.1
""",
    )
    w(
        "examples/04_multi_adapter/env.example",
        """MCP_GW_CONFIG=./gateway.yaml
MCP_GW_ADAPTERS=flink,kafka,spark,iceberg
FLINK_REST_URL=http://localhost:8081
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
SCHEMA_REGISTRY_URL=http://localhost:8081
SPARK_HISTORY_URL=http://localhost:18080
SPARK_LIVY_URL=http://localhost:8998
ICEBERG_REST_CATALOG_URL=http://localhost:8181
""",
    )

    w(
        "examples/05_cursor_mcp_config/README.md",
        """# Cursor IDE MCP config

Use an absolute path to the shade jar. Sample JSON is in `mcp.json`.

Logs go to stderr; Cursor uses stdout for MCP JSON-RPC only.
""",
    )
    w(
        "examples/05_cursor_mcp_config/mcp.json",
        """{
  "mcpServers": {
    "aegis": {
      "command": "java",
      "args": ["-jar", "REPLACE/WITH/aegis-mcp-gateway-0.1.0-all.jar"],
      "env": {
        "MCP_GW_TRANSPORT": "stdio",
        "MCP_GW_ADAPTERS": "flink",
        "FLINK_REST_URL": "http://localhost:8081",
        "MCP_GW_LOG_LEVEL": "WARN"
      }
    }
  }
}
""",
    )

    w(
        "examples/docker-compose/docker-compose.yml",
        """services:
  aegis:
    image: eclipse-temurin:17-jre
    working_dir: /opt/aegis
    volumes:
      - ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar:/opt/aegis/aegis-mcp-gateway.jar:ro
    environment:
      MCP_GW_TRANSPORT: http
      MCP_GW_HTTP_HOST: 0.0.0.0
      MCP_GW_HTTP_PORT: "8090"
      MCP_GW_AUTH_MODE: tokenfile
      MCP_GW_HTTP_BEARER_TOKEN: local-dev-only-change-me
      MCP_GW_ADAPTERS: flink
      FLINK_REST_URL: http://host.docker.internal:8081
    ports:
      - "8090:8090"
    entrypoint: ["java", "-jar", "/opt/aegis/aegis-mcp-gateway.jar"]
""",
    )
    w(
        "examples/docker-compose/README.md",
        """# Docker Compose

Requires the shade jar on the host.

```bash
mvn -q -pl mcp-gateway-dist -am package -DskipTests
docker compose up
```

Health: `http://localhost:8090/healthz`.
MCP calls need `Authorization: Bearer local-dev-only-change-me`.
""",
    )

    w(
        "docs/getting-started.md",
        """# Getting started

Author: Viquar Khan. Apache License 2.0.

## Build

```bash
mvn clean verify
```

Runnable jar:

`mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar`

## Minimal run (Flink, read-only, stdio)

```bash
export MCP_GW_ADAPTERS=flink
export FLINK_REST_URL=http://localhost:8081
java -jar mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

## Next steps

- Examples under [../examples](../examples)
- Operations: [operations.md](operations.md)
- Adapters: [adapters.md](adapters.md)
- Design: [DESIGN-apache-mcp-gateway.md](DESIGN-apache-mcp-gateway.md)
""",
    )

    w(
        "docs/operations.md",
        """# Operations

Author: Viquar Khan. Apache License 2.0.

## Transports

| Mode | Env | Notes |
| --- | --- | --- |
| stdio | `MCP_GW_TRANSPORT=stdio` | Default. stdout = MCP JSON-RPC only |
| http | `MCP_GW_TRANSPORT=http` | Requires inbound auth; prefer TLS off-loopback |

## Authentication

| Mode | Status |
| --- | --- |
| tokenfile / bearer | Production-ready for 0.1 |
| oauth | JWKS JWT validation when `MCP_GW_OAUTH_JWKS_URL` set |
| cimd / spiffe | Fail-closed stubs; do not enable in prod yet |

Token file format: `callerId:sha256:jobs:jars:readonly[:outboundAuth]`

## Writes

Require `MCP_GW_WRITE_ENABLED=true` and `MCP_GW_APPROVAL_SECRET`. Mint tokens with
`Approval` / `ApprovalTokens` CLI. Tokens are single-use and scope-bound.

## Health

- `/healthz` liveness
- `/readyz` readiness (fails after shutdown `markNotLive`)
- `/metrics` Prometheus text

## HA limitations (0.1)

Rate limiter, approval nonces, and audit log are in-process. Use a single replica or accept
per-replica limits until Redis-backed stores land.

## Logging

Logback to stderr. Never write application logs to stdout.
""",
    )

    w(
        "docs/adapters.md",
        """# Adapters

Author: Viquar Khan. Apache License 2.0.

Enable with `MCP_GW_ADAPTERS=flink,kafka,spark,iceberg`.

| Adapter | Taxonomy | Key env | Notes |
| --- | --- | --- | --- |
| flink | streaming | `FLINK_REST_URL`, SQL Gateway URL | Full REST/SQL; reference implementation |
| kafka | messaging | `KAFKA_BOOTSTRAP_SERVERS`, `SCHEMA_REGISTRY_URL` | AdminClient + SR HTTP |
| spark | batch | `SPARK_HISTORY_URL`, `SPARK_LIVY_URL` | History reads; Livy submit/kill |
| iceberg | lakehouse | `ICEBERG_REST_CATALOG_URL` | REST catalog reads; mutate via REST where supported |

Tool catalogs: `mcp-adapter-*/src/main/resources/adapters/*/tools.yaml`.

Core filters registration by write unlock and `toolsAllowed`. Adapters never allow or deny.
""",
    )

    w(
        "CHANGELOG.md",
        """# Changelog

## 0.1.0

- Multi-module Aegis MCP Governance Gateway
- Core interceptor chain (10 steps), auth, FinOps, integrity, YAML manifests
- Flink adapter (REST + SQL Gateway) with offline tests
- Kafka, Spark, Iceberg adapters
- Dist shade jar, Docker, Helm charts
- Examples and operations docs
""",
    )

    w(
        "SECURITY.md",
        """# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| 0.1.x | Yes |

## Reporting

Report vulnerabilities privately to the maintainers. Do not open public issues for exploitable
flaws until a fix is available.

## Product defaults

- Writes locked unless explicitly unlocked with an approval secret
- HTTP requires inbound credentials
- Cloud metadata addresses are always egress-denied
- Tool output is bounded and redacted
- OAuth without JWKS remains fail-closed
""",
    )

    w(
        "CONTRIBUTING.md",
        """# Contributing

## Build

```bash
mvn clean verify
```

JDK 17+, Maven 3.9+.

## Rules

- UTF-8 sources without BOM
- No `System.out.println` (stdout is MCP JSON-RPC)
- No em dashes in Markdown
- Do not invent SEP or KIP numbers; cite DESIGN / LLD or leave TODO
- Engine clients belong only in adapter modules
- Prefer tests with embedded HTTP fakes (deterministic, offline)

## Style

Match existing packages under `io.github.vaquarkhan.aegis`. Apache license header on every Java
file. Keep PRs focused.
""",
    )

    w(
        "CODE_OF_CONDUCT.md",
        """# Code of Conduct

This project follows a simple respectful collaboration standard: be kind, assume good intent,
and focus critique on ideas and code. Harassment or discrimination is not welcome.

Maintainers may remove contributions or ban participants that violate this standard.
""",
    )

    w(
        "ROADMAP.md",
        """# Roadmap

## Done in 0.1.0

- Governed multi-adapter MCP gateway core
- Flink reference adapter
- Kafka / Spark / Iceberg adapters
- Docs, examples, dist packaging

## Next

- Redis-backed rate/nonce/audit for multi-replica HA
- OpenTelemetry spans
- Full CIMD and SPIFFE verification
- Protocol interceptor discovery when SEP-2624 (or successor) is accepted
- `Mcp-Method` / `Mcp-Name` enforcement profile for WAFs
""",
    )

    print("product docs + examples complete")


if __name__ == "__main__":
    main()
