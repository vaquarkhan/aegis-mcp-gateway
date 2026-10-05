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
# Apache Airflow: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/airflow/airflow.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`airflow.yaml`](../../../adopters/configurations/adapters/airflow/airflow.yaml).
`AdapterConfigsTest` exercises every declared request shape and its governance lifecycle against a local
contract endpoint. That is **not** a real Airflow claim: this folder deliberately has no `results/`, captured
Aegis YAML, or `HOW-TO-RUN.md` because no Airflow service was started here.

## What the profile governs

The declarative HTTP source targets Airflow’s stable REST API: health, DAG listing and detail, DAG run
creation, and DAG deletion. The profile classifies writes and destructive calls, so Aegis policy, approval,
dry-run and audit controls apply before a request reaches Airflow.

## What a real two-door run needs

A real run requires an Airflow webserver plus scheduler and an initialized metadata database, with a DAG the
test can trigger and delete safely. Then the test must run the same YAML two ways: own-host Aegis and a real
Knox 3.0.0 route. Because this is a declarative `http` source, Knox needs only the Aegis Knox bundle in
`ext/`; no source plugin jar is required. The canonical YAML is the configuration to start from; substitute
the webserver address, egress allowlist, credentials and production groups.

The [tested environment](../../../docs/VALIDATION.md#tested-environments) has neither Docker nor an installed WSL distribution, so it cannot stand up the required
Linux-oriented Airflow/database service stack without fabricating an endpoint. Airflow's own prerequisites
page states that it runs on POSIX-compliant systems and, on Windows, through WSL2 or Linux containers
([Airflow prerequisites](https://airflow.apache.org/docs/apache-airflow/stable/installation/prerequisites.html)).
No engine run is recorded.

## API version: Airflow 2.x only, as written

The profile's routes are the Airflow 2 stable REST API under `/api/v1` (base URL
`http://airflow-webserver:8080/api/v1`). Airflow 3 replaced `/api/v1` with the FastAPI-based `/api/v2`
and moved the webserver role to `airflow api-server`
([Upgrading to Airflow 3](https://airflow.apache.org/docs/apache-airflow/stable/installation/upgrading_to_airflow3.html)).
Against Airflow 3 this profile needs new routes, request bodies and authentication, and its own real run,
before it can be used.
