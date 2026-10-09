<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Burr adopter

`burr.yaml` governs ten Apache Burr (incubating) tracking-server operations for project, application, trace and annotation data.

## Why this source

Burr exposes a tracking HTTP API. The route declarations were checked against Burr source rather than prose, then expressed as declarative Aegis `http` tools.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/burr/burr.yaml
```

Replace the tracking-server URL, authorization, groups and egress policy. Keep trace-reading tool exposure narrow because traces can contain prompts and model output.

## Evidence

The routes are source-checked and the profile is governance-tested. A live Burr tracking-server run remains pending.
