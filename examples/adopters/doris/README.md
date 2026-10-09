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
# Apache Doris: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/doris/doris.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`doris.yaml`](../../../adopters/configurations/adapters/doris/doris.yaml). The profile
uses Doris Frontend HTTP endpoints for bootstrap/system/process inspection and bounded read-only SQL. The
contract test confirms every declared request is routed and governed; it is not a running Doris cluster.

A real two-door run needs a Doris Frontend and Backend fixture with a disposable database/table and a user able
to inspect it. This is declarative HTTP, so Knox requires only the standard Aegis extension/topology. Substitute
the Frontend URL, egress allowlist, credentials and groups in the canonical YAML. Doris has no installed native
runtime in the [tested environment](../../../docs/VALIDATION.md#tested-environments); no results or testing YAML are claimed until that real service is run.
