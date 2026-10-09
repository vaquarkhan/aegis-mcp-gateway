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
# Apache Hadoop HDFS: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/hadoop/hadoop.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`hadoop.yaml`](../../../adopters/configurations/adapters/hadoop/hadoop.yaml) over
WebHDFS: status/list reads, directory creation and destructive delete. It uses a path-typed argument, so the
engine validates each path segment and refuses traversal before backend routing. The contract test covers those
request shapes and governance behavior.

A real two-door run requires a NameNode/DataNode runtime with WebHDFS enabled and a disposable `/user/...`
fixture. The source is declarative HTTP; Knox needs the Aegis bundle/topology only. Substitute the NameNode URL,
egress CIDRs, credentials and groups in the canonical YAML. No HDFS runtime exists in the [tested environment](../../../docs/VALIDATION.md#tested-environments) and no WSL or
Docker runtime is available, so no HDFS run is recorded.
