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
# Apache NiFi: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/nifi/nifi.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`nifi.yaml`](../../../adopters/configurations/adapters/nifi/nifi.yaml): about/root
flow/process-group reads and governed process-group state updates. The profile’s deterministic contract test
covers the required revision/body request shape and approval flow.

A real two-door run needs a NiFi service with an isolated process group and its current revision, because NiFi
state updates are optimistic-concurrency operations. This is declarative HTTP, so Knox needs the standard Aegis
bundle/topology only. Substitute the NiFi URL, TLS/credentials if enabled, egress policy and groups in the
canonical YAML. NiFi is not installed in the [tested environment](../../../docs/VALIDATION.md#tested-environments), so this profile has no claimed end-to-end evidence.
