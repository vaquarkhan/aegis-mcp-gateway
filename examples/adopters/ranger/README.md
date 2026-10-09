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
# Apache Ranger: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/ranger/ranger.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`ranger.yaml`](../../../adopters/configurations/adapters/ranger/ranger.yaml) over
Ranger Admin’s public v2 API: service/policy reads and destructive policy deletion. Its request shapes and
governance controls are covered by the deterministic config contract test.

A real two-door run needs Ranger Admin, its database, a service definition and a disposable policy. Ranger is
a source release requiring a service stack, not a standalone engine here. The source is declarative HTTP; Knox
needs the Aegis extension/topology only. Replace Admin URL, egress policy, credentials and groups in the
canonical YAML. No Ranger Admin service is running in the [tested environment](../../../docs/VALIDATION.md#tested-environments); no engine run is recorded.
