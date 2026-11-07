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
# Aegis 2.0 history

This document records the product boundary between the legacy Aegis 0.1 distribution and Aegis
2.0. It is a transition record, not a substitute for release notes, security advisories or Git
history.

## Current state

The Aegis 2.0 reactor is the repository root on the 2.0 review branch. The 0.1 implementation
(`mcp-gateway-core`, `mcp-gateway-dist`, the `mcp-adapter-*` modules and their documentation and
examples) has been removed from this line. Its source remains in Git history and in the released
0.1.0 artifacts. Aegis 2.0 is pre-release (`2.0.0-SNAPSHOT`); no 2.0 release has been published.

## What changed in 2.0

| Area | 0.1 record | 2.0 direction |
| --- | --- | --- |
| Backend integrations | One `mcp-adapter-<engine>` Maven module per engine | Named declarative profiles under `adopters/configurations/adapters/<adopter>/`, with typed source modules only when a real client protocol requires one |
| ActiveMQ | `mcp-adapter-activemq` Java module | `adopters/configurations/adapters/activemq/activemq.yaml`, governed through the generic HTTP source |
| Hive | `mcp-adapter-hive` Java module | `adopters/configurations/adapters/hive/hive.yaml` with the shared `aegis-source-jdbc` module and an operator-supplied HiveServer2 driver |
| Common source protocols | Adapter-local implementations | Shared typed source modules, currently including JDBC, ZooKeeper, Spark Connect and Kafka Admin capability |
| Operator guidance | Flat and module-specific material | Named configuration, tutorial and framework directories, each with a developer README |
| Governance | Implemented in the 0.1 core | One 2.0 governance engine shared by own-host and optional host integrations |

The configuration inventory deliberately does not retain former product-named HTTP configurations
when their endpoints were not verified as real product APIs. See
[COVERAGE-0.1-VS-2.0.md](COVERAGE-0.1-VS-2.0.md) and
[VALIDATION.md](VALIDATION.md) for the executable inventory and evidence status.

## Where the 0.1 source lives

The 0.1 modules are not copied, vendored or referenced by the 2.0 build. Anyone who needs them reads
the Git history of the 0.1 line, or the 0.1.0 release. They must not be restored into
this line or used to claim 2.0 support; the canonical 2.0 integration path is the named profile or
shared typed-source module in this repository.

## Repository layout changes from 0.1 to 2.0

| Change | Result |
| --- | --- |
| Repository root | The 2.0 Maven reactor is the repository root; the former nested `aegis-gateway-2.0/` directory is no longer used |
| 0.1 build | The 0.1 modules, root 0.1 build, 0.1 documentation, examples and the 0.1 CI workflow are not part of the 2.0 line |
| Community files | Root README, NOTICE, CHANGELOG, CONTRIBUTING, SECURITY and AGENTS describe 2.0 |
| Release evidence | The full 2.0 build, link, inventory and target evidence is rerun on the release-candidate tree before a 2.0 release |

## Maintaining this record

Update this document when the product boundary changes or a legacy capability gains a verified 2.0
successor. Keep evidence claims in
[evidence/CLAIMS-MATRIX.md](evidence/CLAIMS-MATRIX.md) and
[VALIDATION.md](VALIDATION.md), rather than duplicating test results here.
