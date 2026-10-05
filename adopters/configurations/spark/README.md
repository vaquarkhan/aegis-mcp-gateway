<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Spark profile

`spark.yaml` governs Spark History Server or driver-UI reads plus standalone-master submission status,
kill and approved-jar submission operations.

## Why this source

The selected Spark UI and standalone-master surfaces are HTTP. The standalone write routes apply only
when the target master enables its REST server. Spark Connect is a different protocol and has its own
[spark-connect](../spark-connect/README.md) profile.

## Run

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar `
  --config adopters/configurations/spark/spark.yaml
```

Replace History/driver and master URLs, groups, egress CIDRs, audit path and jar allow-list policy.

## Evidence

`RealSparkTest` validates the read path against a real local Spark 4.0.1 driver UI. Standalone submission
and kill require a separately configured standalone-master run.
