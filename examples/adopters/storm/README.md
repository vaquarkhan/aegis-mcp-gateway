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
# Apache Storm: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/storm/storm.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`storm.yaml`](../../../adopters/configurations/adapters/storm/storm.yaml) over the
Storm UI API: cluster/topology reads and governed topology kill. The profile contract and approval lifecycle
are tested deterministically, but no real Storm cluster has run here.

A real two-door run needs Nimbus, Storm UI, ZooKeeper, a submitted disposable topology and a safe kill target.
The profile is declarative HTTP; Knox needs the standard Aegis extension/topology only. Substitute the UI URL,
egress policy, credentials and groups in the canonical YAML. The [tested environment](../../../docs/VALIDATION.md#tested-environments) does not provide a Storm/ZooKeeper service
set, so no result YAML or transcript exists.
