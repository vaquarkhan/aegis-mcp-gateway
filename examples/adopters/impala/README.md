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
# Apache Impala: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/impala/impala.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`impala.yaml`](../../../adopters/configurations/adapters/impala/impala.yaml) over an
Impala coordinator’s debug web API: metrics, queries and governed query cancellation. The profile contract is
tested, but Apache Impala publishes source releases and a real coordinator normally depends on an integrated
Hadoop/Hive ecosystem.

A real two-door run needs an operational coordinator with a known cancellable query and the required HDFS/Hive
services. The source is declarative HTTP; Knox needs only the Aegis extension/topology. Replace coordinator
URL, egress policy, credentials and groups in the canonical YAML. There is no native Impala stack in the [tested environment](../../../docs/VALIDATION.md#tested-environments),
so no testing YAML or result logs are fabricated.
