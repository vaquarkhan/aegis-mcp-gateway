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

# Quickstart Guide: Aegis MCP Gateway (Docker Backend)

This quickstart demonstrates how to launch an **Apache Flink** backend in Docker, start the **Aegis MCP Gateway** in front of it using the maintained Flink tutorial configuration, and send governed MCP payloads through the gateway.

> **Looking for a full walkthrough?**
> - See [docs/TUTORIAL-FLINK.md](TUTORIAL-FLINK.md) for non-Docker local setup (`start-flink.ps1` / `start-flink.sh`) and end-to-end governance scenarios (approvals, dry runs, and policy enforcement).
> - See [docs/TUTORIALS.md](TUTORIALS.md) for other backend adapters.
> - See [adopters/configurations/template/TEMPLATE.yaml](../adopters/configurations/template/TEMPLATE.yaml) to author a custom configuration.

---

## Prerequisites

- **Java:** OpenJDK 17 or higher installed (`java -version`).
- **Docker:** Docker Engine or Docker Desktop running.
- **OS:** Windows (PowerShell) or Unix-based terminal (macOS / Linux).
- **Target Artifact:** Built `aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar`.

---

## 1. Environment Setup & Signing Key Generation

Aegis requires a 32-byte (or greater) signing key exported to the environment variable specified by `approval.keyEnv` (default: `AEGIS_SIGNING_KEY`).

### Windows (PowerShell)

```powershell
# Generate a 32-byte hex key
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
[BitConverter]::ToString($bytes).Replace('-', '')

# Export the generated key (replace placeholder with the output above)
$env:AEGIS_SIGNING_KEY = "<generate-32-byte-hex>"
```

### Linux / macOS (Bash / Zsh)

```bash
export AEGIS_SIGNING_KEY=$(openssl rand -hex 32)
```

---

## 2. Start the Apache Flink Backend in Docker

Aegis governs the **Apache Flink JobManager REST API** (`flink_list_jobs`, `flink_get_job`, `flink_run_jar`, and `flink_cancel_job`). Start a Flink JobManager container and map its REST API port (`8081`) to `127.0.0.1:18081` to match [adopters/tutorials/flink/aegis-flink-tutorial.yaml](../adopters/tutorials/flink/aegis-flink-tutorial.yaml):

```bash
docker run -d --name flink-jobmanager \
  -p 127.0.0.1:18081:8081 \
  -e FLINK_PROPERTIES="jobmanager.rpc.address: localhost" \
  apache/flink:1.18.0 jobmanager
```

Verify that the Flink JobManager REST API is reachable before starting Aegis:

### Windows (PowerShell)

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:18081/overview"
```

### Linux / macOS (Bash)

```bash
curl -s http://127.0.0.1:18081/overview
```

> **Note:** A TaskManager container is not required to test cluster inspection (`READ`) tools or governance policies. For production deployments targeting the default JobManager port `8081` directly, see [adopters/configurations/flink/flink.yaml](../adopters/configurations/flink/flink.yaml).

---

## 3. Configuration & Access Controls

Rather than duplicating configuration blocks, this quickstart uses [adopters/tutorials/flink/aegis-flink-tutorial.yaml](../adopters/tutorials/flink/aegis-flink-tutorial.yaml), which configures:

- **Gateway Listener (`host.listen` / `host.identity`):** Binds Aegis to `http://127.0.0.1:8080/mcp` with static bearer tokens mapped to `flink-users`, `flink-operators`, and `flink-approvers`.
- **Backend HTTP Source (`sources`):** Proxies declarative `flink:job` and `flink:jar` REST calls (`request: {method, path}`, `inputSchema`, and `dryRun` rules) to `http://127.0.0.1:18081`.
- **Policy & Approvals (`policy` / `approval`):** Enforces role-based access control and human-in-the-loop approval for `MUTATE` and `DESTRUCTIVE` actions.

### Role-Based Access Matrix

| Role / Caller | Tool Class | Example Flink Operation | Result |
| :--- | :--- | :--- | :--- |
| **Analyst** (`flink-users`) | `READ` | `flink_list_jobs`, `flink_get_job` | **Allowed** |
| **Analyst** (`flink-users`) | `MUTATE` / `DESTRUCTIVE` | `flink_run_jar`, `flink_cancel_job` | **Denied** |
| **Operator** (`flink-operators`) | `READ` | `flink_list_jobs`, `flink_get_job` | **Allowed** (granted `READ` in policy) |
| **Operator** (`flink-operators`) | `MUTATE` / `DESTRUCTIVE` | `flink_run_jar`, `flink_cancel_job` | **Eligible**, subject to Approval |
| **Approver** (`flink-approvers`) | Approval | Approve pending requests | **Eligible** if rules permit |

---

## 4. Start the Aegis MCP Gateway

From the repository root, export the identity tokens expected by [adopters/tutorials/flink/aegis-flink-tutorial.yaml](../adopters/tutorials/flink/aegis-flink-tutorial.yaml) and launch the gateway JAR:

### Windows (PowerShell)

```powershell
$env:ANALYST_TOKEN = "test-analyst-token"
$env:OPERATOR_TOKEN = "test-operator-token"
$env:APPROVER_TOKEN = "test-approver-token"

java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/tutorials/flink/aegis-flink-tutorial.yaml
```

### Linux / macOS (Bash)

```bash
export ANALYST_TOKEN=test-analyst-token
export OPERATOR_TOKEN=test-operator-token
export APPROVER_TOKEN=test-approver-token

java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/tutorials/flink/aegis-flink-tutorial.yaml
```

**Expected Startup Output:**

```text
INFO: Aegis gateway listening on http://127.0.0.1:8080/mcp
```

---

## 5. Send Client Payloads Through Aegis

Open a second terminal window to send MCP JSON-RPC requests through the gateway.

### 5.1 Initialize MCP Session & Invoke a Governed Tool (`PowerShell`)

```powershell
$env:ANALYST_TOKEN = "test-analyst-token"

# 1. Initialize MCP session
$init = Invoke-WebRequest -Method Post -Uri "http://127.0.0.1:8080/mcp" `
  -Headers @{
    "Authorization" = "Bearer $env:ANALYST_TOKEN"
    "Accept" = "application/json, text/event-stream"
    "MCP-Protocol-Version" = "2025-03-26"
  } `
  -ContentType "application/json" `
  -Body '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}'

$sessionId = $init.Headers["Mcp-Session-Id"]

# 2. Call flink_list_jobs through Aegis
$resp = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/mcp" `
  -Headers @{
    "Authorization" = "Bearer $env:ANALYST_TOKEN"
    "Accept" = "application/json, text/event-stream"
    "MCP-Protocol-Version" = "2025-03-26"
    "Mcp-Session-Id" = $sessionId
  } `
  -ContentType "application/json" `
  -Body '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"flink_list_jobs","arguments":{}}}'

$resp | ConvertTo-Json -Depth 5
```

### 5.2 Initialize MCP Session & Invoke a Governed Tool (`Bash / curl`)

```bash
export ANALYST_TOKEN=test-analyst-token

# 1. Verify GET returns 405 Method Not Allowed (POST is required)
curl -i -H "Authorization: Bearer $ANALYST_TOKEN" http://127.0.0.1:8080/mcp

# 2. Initialize MCP session (copy the returned Mcp-Session-Id header)
curl -i -X POST http://127.0.0.1:8080/mcp \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}'

# 3. Call flink_list_jobs through Aegis (replace <session-id>)
curl -i -X POST http://127.0.0.1:8080/mcp \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "Mcp-Session-Id: <session-id>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"flink_list_jobs","arguments":{}}}'
```

---

## 6. Verify the Hash-Chained Audit Log

Aegis writes structured, hash-chained audit events (`ALLOW_INTENT` and `EXECUTION_RESULT`) to the JSONL audit file configured in `adopters/tutorials/flink/aegis-flink-tutorial.yaml` rather than printing per-request logs to stdout.

### Windows (PowerShell)

```powershell
Get-Content adopters/tutorials/flink/work/audit/aegis-flink-tutorial.jsonl -Tail 5
```

### Linux / macOS (Bash)

```bash
tail -n 5 adopters/tutorials/flink/work/audit/aegis-flink-tutorial.jsonl
```

---

## Cleanup

Stop and remove the Flink container when finished:

```bash
docker rm -f flink-jobmanager
```

---

## Next Steps

- Follow [docs/TUTORIAL-FLINK.md](TUTORIAL-FLINK.md) to test `MUTATE` and `DESTRUCTIVE` tool approvals (such as `flink_run_jar` and `flink_cancel_job`).
- Copy [adopters/configurations/template/TEMPLATE.yaml](../adopters/configurations/template/TEMPLATE.yaml) to configure your own HTTP or stdio backend.