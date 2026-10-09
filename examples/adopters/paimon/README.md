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
# Apache Paimon: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/paimon/paimon.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`paimon.yaml`](../../../adopters/configurations/adapters/paimon/paimon.yaml) over the
Paimon REST catalog: databases/tables reads and destructive table deletion. The config contract is tested;
there is no real Paimon REST catalog result in this repository yet.

A real two-door run needs Paimon’s REST catalog service plus a configured warehouse/storage backend and a
disposable table. As a declarative HTTP source it needs no Paimon plugin in Knox; deploy the Aegis extension
and topology and replace catalog URL, egress policy, credentials and groups. No configured Paimon catalog is
available in the [tested environment](../../../docs/VALIDATION.md#tested-environments), so no run logs or copied YAML snapshot are present.
