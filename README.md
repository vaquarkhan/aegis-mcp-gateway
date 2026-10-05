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
# Aegis Gateway 2.0

Aegis is a configuration-driven governance layer for MCP (Model Context Protocol) calls. It places one fixed, fail-closed decision path between an agent and real systems: typed resource scope, policy, egress control, rate/budget controls, screening, independent approval, destructive-action receipts, and hash-chained audit intent all run before a backend credential is resolved or a backend is called.

Aegis is a pre-release MCP action-governance engine. Compatibility is asserted only per target version
and evidence record. Broader adapter validation, shared catalog state, external audit anchoring, and
additional delegated-credential integrations are planned work listed in the [roadmap](docs/ROADMAP.md).
Built-in RFC 8693 token exchange is verified only for the own host with JWT identity and Keycloak.

![Aegis architecture: shared governance core with own-host and Apache Knox integration choices](docs/assets/aegis-architecture.svg)

A destructive action is governed as a state transition, not as a privileged HTTP call:

![Aegis approval and execution flow](docs/assets/aegis-governance-flow.png)

## Start here

Read the task-focused [Aegis Gateway handbook](docs/HANDBOOK.md). It explains MCP governance, the risk of ungoverned agent actions, the Aegis MCP-first architecture, modules, YAML, evidence, and deployment choices. A visual version is available at [docs/guide/handbook.html](docs/guide/handbook.html).

| If you need to... | Start with |
| --- | --- |
| Understand MCP governance, risks, and Aegis controls | [Handbook](docs/HANDBOOK.md) → [Security](docs/SECURITY.md) |
| Put an existing MCP server or REST API behind Aegis | [Adoption guide](docs/ADOPTION-GUIDE.md) → [Configuration](docs/CONFIGURATION.md) |
| Use Aegis in an existing Knox topology | [Aegis Knox extension guide](docs/KNOX-EXTENSION.md) |
| Reuse an Apache-engine profile | [Adopter catalog](examples/adopters/README.md) |
| Check retained target verification evidence | [Validation report](docs/VALIDATION.md) → [claims matrix](docs/evidence/CLAIMS-MATRIX.md) |
| Develop a source, hook, or extension | [Developer guide](docs/DEVELOPER.md) |
| Try it end to end from an IDE, with Spark and Iceberg | [Spark and Iceberg tutorial](docs/TUTORIAL-SPARK-ICEBERG.md) → [all tutorials](docs/TUTORIALS.md) |

## Aegis host integrations

Aegis owns the MCP action decision in every deployment. It can expose that decision through its own host, or through an optional extension when an organization already operates Apache Knox.

| Aegis integration | Edge identity and routing | Aegis MCP governance |
| --- | --- | --- |
| **Aegis own host** (`aegis-gateway`) | Aegis verifies JWT via JWKS or HS256, or hashed static tokens; Aegis applies TLS and route policy. | The standard own-host executable invokes the common MCP handler and governance engine. |
| **Optional Knox extension** (`aegis-knox3-extension`) | An existing Knox topology authenticates, establishes identity/groups, and admits the route. | Aegis receives the Knox subject, then parses and governs the MCP action with the same engine. |

Apache Knox and Aegis address distinct, composable layers. A Knox topology can provide the HTTP perimeter, identity, and route admission in deployments that already use Knox; Aegis applies semantic governance to each MCP action after that admission. Aegis also offers its own host for deployments where that is the appropriate operational choice.

## Build and verify

JDK 17+ and Maven 3.9+ are required. The default build runs without external services or network access.

```powershell
mvn -B clean install
```

The validation report records deterministic governance evidence, retained target-version verification evidence, and clearly scoped contract profiles. Use the [adopter catalog](examples/adopters/README.md) to select the profile and evidence level appropriate for your deployment.

## Evidence rule

Aegis ships **15 adopter profiles with retained real two-door runs** (own host, and a stock Apache Knox 3.0.0 process) and **16 configuration/contract-supported profiles** that have no engine run recorded. The catalog includes **25 deployable adapter configurations and 115 tools**. Every profile with a retained run has redacted own-host and Knox configuration, engine configuration, a runnable guide, and retained results. The two-door adopter runs were rerun on 2026-10-03 on the current working tree; each is rerun on a release candidate before it supports a release compatibility claim. The [adopter catalog](examples/adopters/README.md) identifies which is which and why a target remains unverified.

## Project references

- [User handbook](docs/HANDBOOK.md)
- [Architecture](docs/ARCHITECTURE.md) and [configuration reference](docs/CONFIGURATION.md)
- [Module reference](docs/guide/modules.html): purpose, configuration keys and security controls of every module
- [Security properties and documented limits](docs/SECURITY.md)
- [Validation and claims evidence](docs/VALIDATION.md)
- [Apache Knox extension](docs/KNOX-EXTENSION.md)
- [Real-engine examples and contract profiles](examples/adopters/README.md)
- [Container image](Dockerfile) and [Helm chart](charts/aegis/README.md); [reference hook services](adopters/hooks/README.md); [A2A agent profile](adopters/configurations/a2a/README.md); [per-caller credentials with token exchange](docs/CONFIGURATION.md#per-caller-credentials-rfc-8693-token-exchange)
- [Roadmap](docs/ROADMAP.md), [A2A decision: agents as tool sources, no A2A front door](docs/adr/ADR-001-A2A-AS-TOOL-SOURCE.md) and [Knox 3.1 Jakarta target](docs/adr/ADR-002-KNOX-3.1-JAKARTA-TARGET.md)

## Status

`2.0.0-SNAPSHOT`, pre-release. No release has been published. Contributor, governance, security, provenance, and release rules are in [CONTRIBUTING.md](CONTRIBUTING.md), [GOVERNANCE.md](GOVERNANCE.md), [SECURITY.md](SECURITY.md), and [AGENTS.md](AGENTS.md).
