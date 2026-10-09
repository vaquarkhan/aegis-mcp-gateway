<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Spark Connect profile

`spark-connect.yaml` governs Spark Connect through the typed `spark-connect` source. It exposes
metadata and dataframe reads, explain, and destructive table drop or SQL only under explicit controls.

## Why this source

Spark Connect is gRPC, not a REST facade. The profile supplies policy/classes/resources while the shared
`aegis-spark-connect` Java source implements the actual protocol.

## Run

Use the gateway assembly containing `aegis-spark-connect`:

```powershell
java -jar examples/aegis-gateway-spark/target/aegis-gateway-spark-2.0.0-SNAPSHOT-all.jar `
  --config adopters/configurations/spark-connect/spark-connect.yaml
```

Replace host, port, TLS/token settings, groups, egress policy and protected-table deny rules.

## Evidence

`RealSparkConnectServerTest` starts a real Spark Connect 4.0.1 server and verifies reads, dry-run,
independent approval and a real destructive drop on the current profile.
