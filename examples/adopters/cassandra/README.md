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
# Apache Cassandra: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/cassandra/cassandra.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`cassandra.yaml`](../../../adopters/configurations/adapters/cassandra/cassandra.yaml).
The profile targets the **Cassandra Sidecar** native REST API, not CQL directly; the deterministic contract
test covers its keyspace/table reads, token ranges and destructive table delete governance.

A real two-door run needs a Cassandra node plus a matching Sidecar process, a `sales` keyspace/table fixture,
and a safe disposable table for deletion. The source is declarative HTTP; Knox requires the Aegis bundle and
topology, not a Cassandra plugin. Replace the Sidecar address, egress allowlist, credentials and groups in the
canonical YAML. No Sidecar/Cassandra deployment is installed in the [tested environment](../../../docs/VALIDATION.md#tested-environments), so this folder intentionally has no
captured YAML or result logs.
