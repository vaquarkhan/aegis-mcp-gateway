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
# Apache HBase: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/hbase/hbase.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`hbase.yaml`](../../../adopters/configurations/adapters/hbase/hbase.yaml) over HBase
REST (Stargate): cluster/tables/schema reads and destructive table schema delete. The deterministic contract
test covers all profile tools and their controls.

A real two-door run needs HBase, ZooKeeper, HDFS and the HBase REST service with a disposable table. This is a
declarative HTTP source, so the Knox deployment is standard Aegis extension plus topology, without an HBase
plugin. Substitute the REST endpoint, egress allowlist, credentials and groups in the canonical YAML. The
required HBase stack is not available in the [tested environment](../../../docs/VALIDATION.md#tested-environments), so no real-run inputs or logs are recorded.
