<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Hadoop WebHDFS adopter

`hadoop.yaml` governs four WebHDFS operations: status, file status, directory creation and destructive path deletion.

## Why this source

WebHDFS is HTTP. Paths are extracted as typed resources and path-aware templates reject traversal before a backend request is made.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/hadoop/hadoop.yaml
```

Replace the NameNode URL, credentials, groups, egress policy and protected path rules. Keep recursive deletion absent unless a separately reviewed operation is added.

## Evidence

The profile has a route/governance contract test, including path traversal refusal. A target Hadoop version still needs a real-engine run.
