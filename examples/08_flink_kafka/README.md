# Flink + Kafka example

Author: Viquar Khan.

Runs Aegis with both the Flink and Kafka adapters against a local Docker Compose stack
(Flink JobManager/TaskManager and Kafka KRaft). Schema Registry is optional; point
`SCHEMA_REGISTRY_URL` at your own registry if you want `query_schema_registry` in smoke.

## 1. Build the gateway jar

```bash
mvn -q -pl mcp-gateway-dist -am package -DskipTests
```

## 2. Start the stack

```bash
cd examples/08_flink_kafka
docker compose up -d
```

Wait until Flink UI answers (`http://localhost:8081`) and Aegis health is up:

```bash
curl -s http://localhost:8090/healthz
```

## 3. Smoke (read-only)

Bearer token is the plaintext `test` (see `tokens.example.txt`). Streamable HTTP needs an
`initialize` handshake first; the smoke scripts do that and reuse `Mcp-Session-Id`.

```powershell
.\smoke.ps1
```

```bash
chmod +x smoke.sh && ./smoke.sh
```

Manual curl shape:

```bash
# 1) initialize -> capture Mcp-Session-Id response header
# 2) tools/list and tools/call with Authorization + Mcp-Session-Id
```

Host Flink already on `:8081`? Start Kafka only, then run the gateway on the host:

```bash
docker compose -f docker-compose.host-flink.yml up -d
# then the host-only java command in section 4, then .\smoke.ps1
```
## 4. Host-only run (engines already local)

If Flink is on `:8081` and Kafka on `:9092`:

```powershell
$env:MCP_GW_TRANSPORT='http'
$env:MCP_GW_HTTP_HOST='127.0.0.1'
$env:MCP_GW_HTTP_PORT='8090'
$env:MCP_GW_AUTH_MODE='tokenfile'
$env:MCP_GW_AUTH_TOKENS_FILE=(Resolve-Path .\tokens.example.txt)
$env:MCP_GW_ADAPTERS='flink,kafka'
$env:FLINK_REST_URL='http://localhost:8081'
$env:KAFKA_BOOTSTRAP_SERVERS='localhost:9092'
$env:SCHEMA_REGISTRY_URL='http://localhost:8085'
java -jar ..\..\mcp-gateway-dist\target\aegis-mcp-gateway-0.1.0-all.jar
```

## Expected results

| Check | Expected |
| --- | --- |
| `/healthz` | `ok` / healthy body |
| `tools/list` | Includes `list_jobs`, `get_job`, `list_topics`, `describe_topic`; excludes mutate tools while writes are locked |
| `list_jobs` | JSON from Flink REST (often empty jobs array on a fresh cluster) |
| `list_topics` | JSON topic list from Kafka AdminClient |

## Offline confirmation (no Docker)

```bash
mvn -q -pl mcp-adapter-flink,mcp-adapter-kafka -am test
```

Those suites use embedded HTTP fakes / AdminClient fakes and prove backends, validation, and breaker-visible failures without a live cluster.

## Tear down

```bash
docker compose down -v
```
