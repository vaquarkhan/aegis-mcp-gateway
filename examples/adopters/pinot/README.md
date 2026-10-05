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
# Apache Pinot: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/pinot/pinot.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`pinot.yaml`](../../../adopters/configurations/adapters/pinot/pinot.yaml) over the
Pinot Controller API: table/tenant reads and destructive table deletion. `AdapterConfigsTest` verifies all
request shapes and controls against a contract server, not a real Pinot cluster.

A real two-door run needs a Pinot Controller, at least one server and a disposable table fixture. This is
declarative HTTP, so Knox needs only the Aegis extension/topology. Substitute the Controller URL, egress
allowlist, credentials and groups in the canonical YAML. The required Pinot service set is not installed here,
so there are no end-to-end results to mistake for real evidence.
