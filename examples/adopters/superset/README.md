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
# Apache Superset: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/superset/superset.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`superset.yaml`](../../../adopters/configurations/adapters/superset/superset.yaml) over
Superset’s REST API: database/dataset/chart reads and destructive chart delete. Its route shapes and governance
flow are covered by the config contract test, not a real Superset installation.

A real two-door run needs Superset, its metadata database, a configured authentication model and a disposable
chart. The source is declarative HTTP; Knox needs the Aegis extension/topology only. Substitute Superset URL,
egress policy, credential provider and groups in the canonical YAML. Superset and its database are not available
in the [tested environment](../../../docs/VALIDATION.md#tested-environments), so this profile deliberately has no fabricated results.
