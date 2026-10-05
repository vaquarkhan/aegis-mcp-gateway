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
# Apache Hive: profile supported; HS2 Docker proven; FrontDoors Keep not yet retained

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/hive/hive.yaml), not
configuration captured from a real two-door Keep. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples).

Aegis loads and governs [`hive.yaml`](../../../adopters/configurations/adapters/hive/hive.yaml) through the
shared `jdbc` source and HiveServer2’s JDBC driver. This is deliberately not a REST profile: Hive
SQL is a JDBC protocol. The JDBC source classifies and permits only safe read statements in the profile’s
read tool; writes smuggled through it are refused by the source.

## Proven locally (2026-10-05) — not a Keep

Official image `apache/hive:4.0.1` with `SERVICE_NAME=hiveserver2` listens on host port 10000 and
answers `SHOW DATABASES` via `beeline` inside the container. That proves the product process is
runnable. It does **not** yet prove Aegis governance through both front doors.

## What a Keep still needs (roadmap)

1. `HiveFrontDoorsTest` starting HS2 via `DockerContainer` (image already pulled on this host).
2. `hive-jdbc` and its transitive jars on the own-host test classpath and in Knox `ext/` (thin
   `hive-jdbc-4.0.1.jar` alone is not enough).
3. Seed a disposable table outside Aegis; exercise `jdbc_*` reads and a refused write.
4. `-Keep` retention under `examples/adopters/hive/results/` and
   `python tools/build-project-tutorials.py` for the IDE tutorial.

Tracked under [docs/ROADMAP.md](../../../docs/ROADMAP.md) § “Future work — remaining target verification”.
Replace the JDBC URL, driver, credentials, groups and egress policy for your deployment.
