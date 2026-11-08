# Quickstart Guide: Aegis MCP Gateway

This quickstart guide demonstrates how to configure, set up, and launch the **Aegis MCP Gateway** with a backend adapter (e.g., Apache Flink backend).

## Prerequisites

* **Java**: OpenJDK 17 or higher installed.
* **OS**: Windows (CMD / PowerShell) or Unix-based terminal.
* **Backend Gateway**: Apache Flink gateway (or target backend service) running in a separate process (e.g., via Docker container, local process, or remote host).
* **Target File**: Built `aegis-gateway-2.0.0-SNAPSHOT-all.jar`.

## 1. Environment Setup & Signing Key Generation

Aegis requires a 32-byte (or greater) signing key set in the environment variable specified by `approval.keyEnv` (default: `AEGIS_SIGNING_KEY`).

### Generate and Set Signing Key (Windows)

Run PowerShell to generate a cryptographically secure 32-byte hex string, then set it in your environment:

```cmd
:: Generate key in PowerShell
powershell -NoProfile -Command "$rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider; $bytes = New-Object byte[] 32; $rng.GetBytes($bytes); [BitConverter]::ToString($bytes).Replace('-', '')"

:: Set environment variable in CMD
set AEGIS_SIGNING_KEY=8F958422B40CC0F618AA453BBFBD29042FA8156F662A65A2745D0FF080ECC2KC
```

## 2. Start the Backend Adapter (e.g., Flink Gateway)

Before starting the Aegis Gateway, ensure your backend server (e.g., Flink SQL Gateway) is running in a separate process or container on the expected port (`http://localhost:8083`).

### Example: Running Backend via Docker

```bash
docker run -d --name flink-backend -p 8083:8083 flink:latest
```

*Note: Aegis acts as a proxy/gateway sitting in front of this backend adapter.*

## 3. Aegis Configuration (`myconfig.yaml`)

Create a configuration file (e.g., `myconfig.yaml`). Below is a complete configuration (based off adopters\configurations\template\TEMPLATE.md) configuring an HTTP backend source (Apache Flink Gateway) and static token-based authentication.

```yaml
# Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
# Licensed under the Apache License, Version 2.0

gateway:
  name: aegis-flink-gateway

server:
  versions: ["2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26"]
  legacySessions: true
  allowedOrigins: []

approval:
  keyEnv: AEGIS_SIGNING_KEY
  requiredFor: [MUTATE, DESTRUCTIVE]
  requestTtlSeconds: 1800
  tokenTtlSeconds: 600

vrp:
  enabled: true
  ttlSeconds: 900

access:
  writeGroups: [flink-operators]
  destructiveGroups: []
  approverGroups: [flink-approvers]

policy:
  rules:
    - id: read
      groups: [flink-users]
      classes: [READ]

    - id: operate
      groups: [flink-operators]
      classes: [MUTATE, DESTRUCTIVE]

scopes: []

rateLimits: {perCallerPerMinute: 120, perCallerPerToolPerMinute: 60, budgetPerHour: 2000}
breaker: {failureThreshold: 5, openSeconds: 30}
screening: {mode: ENFORCE, toolDefinitions: ENFORCE, decodeBase64: true}
output: {maxBytes: 1048576, useDefaultRedactions: true, redact: []}

egress:
  allowHosts: []
  allowCidrs: []
  checkArgumentUrls: true

audit:
  file: audit/aegis.jsonl
  fsync: true

catalog: {mode: TOFU, pinFile: state/pins.json, refreshSeconds: 0}

# Authentication & Identity Configuration
host:
  identity:
    type: static # Options: static (predefined tokens), jwt (JWT auth), spiffe (mTLS/SPIFFE)
    static:
      tokens:
        - tokenEnv: ANALYST_TOKEN
          actor: flink-analyst
          groups: [flink-users]

        - tokenEnv: OPERATOR_TOKEN
          actor: flink-operator
          groups: [flink-operators]

        - tokenEnv: APPROVER_TOKEN
          actor: flink-approver
          groups: [flink-approvers]

# Backend Adapter Configuration
sources:
  - id: flink_backend
    type: http
    baseUrl: http://localhost:8083
    tools:
      - name: run_sql_readonly
        description: "Execute read-only SQL queries against the Flink backend."
        class: READ

      - name: run_sql
        description: "Execute SQL statements that modify data or database objects."
        class: MUTATE

      - name: execute_statement
        description: "Execute a generic SQL statement against the Flink backend."
        class: MUTATE
```

## 4. Understanding Access Controls & Roles

Aegis controls access based on caller roles and tool operation classes:

| Role / Caller | Tool Class | Action / Permission Level | Result | 
| ----- | ----- | ----- | ----- | 
| **Analyst** (`flink-users`) | `READ` | `SELECT` query execution | **Allowed** | 
| **Analyst** (`flink-users`) | `MUTATE` | `INSERT` / DDL modification | **Denied** | 
| **Operator** (`flink-operators`) | `READ` | Data query | **Allowed** (via policy) | 
| **Operator** (`flink-operators`) | `MUTATE` | Data modification | **Eligible**, subject to Approval | 
| **Approver** (`flink-approvers`) | Approval | Approve pending requests | **Eligible** if rules permit | 

## 5. Starting the Gateway

Set user identity tokens and launch the Aegis gateway JAR:

```cmd
:: Set identity tokens
set ANALYST_TOKEN=test-analyst-token
set OPERATOR_TOKEN=test-operator-token
set APPROVER_TOKEN=test-approver-token

:: Run Aegis Gateway
java -jar aegis-gateway-2.0.0-SNAPSHOT-all.jar --config myconfig.yaml
```

**Expected Startup Output:**

```text
INFO: Aegis gateway listening on http://127.0.0.1:8080/mcp
```

## 6. Testing the Gateway Connection

### 6.1 Verification (GET Request)

Sending a `GET` request should return a `405 Method Not Allowed` because MCP payloads require `POST`.

```cmd
curl -i -H "Authorization: Bearer %ANALYST_TOKEN%" http://127.0.0.1:8080/mcp
```

**Output:**

```http
HTTP/1.1 405 Method Not Allowed
Allow: POST
```

### 6.2 Initialize MCP Session (POST Request)

Send an initialization payload as an **Analyst**:

```cmd
curl -i -X POST http://127.0.0.1:8080/mcp ^
  -H "Authorization: Bearer %ANALYST_TOKEN%" ^
  -H "Content-Type: application/json" ^
  -H "Accept: application/json, text/event-stream" ^
  -H "MCP-Protocol-Version: 2025-03-26" ^
  -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0\"}}}"
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Mcp-Session-Id: x_Y95Sbb8rrf15gjyBs8_tm9y2CCC24CGhEF7fi13c0

{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{"listChanged":false}},"serverInfo":{"version":"2.0.0-SNAPSHOT","name":"aegis-flink-gateway"}}}
```