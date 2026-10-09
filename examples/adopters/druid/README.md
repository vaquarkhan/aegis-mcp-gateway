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
# Apache Druid: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/druid/druid.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`druid.yaml`](../../../adopters/configurations/adapters/druid/druid.yaml): datasource
reads through the Coordinator, bounded SQL through the Router, and destructive datasource deletion. The
contract test verifies the HTTP shape and governance flow; it is not a Druid deployment.

A real two-door run requires Coordinator/Router services plus metadata and deep-storage configuration, then a
disposable datasource. The source is declarative HTTP and has no Knox source plugin; deploy the Aegis extension
and topology, then use the canonical YAML with the Router address, egress allowlist, credentials and groups
replaced. The [tested environment](../../../docs/VALIDATION.md#tested-environments) lacks the multi-process Druid service environment, so it has no real result logs.
