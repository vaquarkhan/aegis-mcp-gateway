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
# Apache Hive: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/hive/hive.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`hive.yaml`](../../../adopters/configurations/adapters/hive/hive.yaml) through the
shared `jdbc` source and HiveServer2’s JDBC driver. This is deliberately not a REST profile: Hive
SQL is a JDBC protocol. The JDBC source classifies and permits only safe read statements in the profile’s
read tool; writes smuggled through it are refused by the source.

A real two-door run needs HiveServer2, a metastore/database service, a Hive JDBC driver and a disposable table.
Because it is a Java source plugin, a Knox run needs both the Aegis bundle and `aegis-source-jdbc` plus the
operator’s Hive JDBC driver in `ext/`; the own-host process needs the same jars on its classpath. Replace the
JDBC URL, driver, credentials, groups and egress policy in the canonical YAML. None of that service stack is
available here, so no real results are claimed.
