<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Flink profile

`flink.yaml` governs Flink JobManager REST operations: job discovery/detail, savepoints, cancellation,
stop, rescale and approved-jar execution.

## Why this source

Flink JobManager is an HTTP control plane, so the tools are declarative `http` configuration. Typed job
and jar resources let policy constrain individual operations. Multipart jar upload and SQL Gateway
session workflows are intentionally not represented as one-call REST tools, because they need multipart or multi-step requests.

## Run

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar `
  --config adopters/configurations/flink/flink.yaml
```

Replace the JobManager URL, groups, approved-jar policy, egress CIDRs, audit file and credentials. Do
not enable `flink_rescale_job` unless the target cluster actually serves rescaling.

## Evidence

`RealFlinkTest` runs the canonical profile against a real local Flink 1.20.1 MiniCluster. Rerun it when
the profile or target version changes.

An independent, runnable Flink MCP server with a signed server catalog and a break-glass drill
against a real engine is in [examples/break-glass-mcp-servers/flink](../../../examples/break-glass-mcp-servers/flink/README.md).
