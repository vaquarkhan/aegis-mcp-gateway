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
# Apache Atlas: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/atlas/atlas.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`atlas.yaml`](../../../adopters/configurations/adapters/atlas/atlas.yaml). Its
contract/governance test covers every configured Atlas v2 request, including the destructive entity delete
approval flow. There is no real Atlas result here: no Atlas service was started and no engine run is recorded.

A real two-door run needs Atlas plus its required Kafka, Solr and HBase dependencies, an isolated type/entity
fixture, and operator credentials. The source is declarative HTTP, so the Knox setup is the standard Aegis
bundle and topology only; no source plugin belongs in Knox `ext/`. Start from the canonical YAML and replace
the Atlas URL, egress allowlist, credentials, groups and audit destination. The [tested environment](../../../docs/VALIDATION.md#tested-environments) lacks the required
multi-service runtime (no Docker/WSL distribution), so the profile remains explicitly not real-engine verified.
