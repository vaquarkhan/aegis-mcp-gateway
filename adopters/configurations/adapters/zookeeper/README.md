<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache ZooKeeper adopter

`zookeeper.yaml` governs eight operations across two real ZooKeeper surfaces: AdminServer HTTP commands and the native Java client protocol for znodes.

## Why this source

ZooKeeper data-tree paths are not a normal REST API, so the adopter combines `http` only for `/commands/*` with the generic `zookeeper` source for znode operations. Do not add the old `/znodes/v1` facade; ZooKeeper does not serve it.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/zookeeper/zookeeper.yaml
```

Replace the ensemble connection string, AdminServer endpoint, groups and protected znode policy. Versioned writes must retain their expected version input.

## Evidence

The znode source is proven against real embedded ZooKeeper. AdminServer command names are checked against ZooKeeper's own registry. Target deployment verification remains required.
