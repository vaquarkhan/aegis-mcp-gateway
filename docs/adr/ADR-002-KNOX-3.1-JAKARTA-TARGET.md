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
# ADR-002: Knox 3.0.0 is the current proof point; Knox 3.1 (Jakarta) is the target

- Status: proposed (for review with Knox maintainers)
- Date: 2026-10-03
- Deciders: the author; open for review with the Knox community

## Context

`aegis-knox3-extension` is a thin Knox service that reads the Subject established by Knox's provider
chain and hands MCP requests to the shared Aegis governance plane. It compiles against the released
Knox 3.0.0 public API in `provided` scope, which uses `javax.servlet` 4.0.1 and `javax.ws.rs` 2.0.

At the Knox trunk snapshot inspected on 2026-10-03, `3.1.0-SNAPSHOT` had moved to the Jakarta
namespace: its root `pom.xml` set `jakarta.servlet-api` 6.0.0, `jakarta.ws.rs-api` 3.1.0, and Jersey
3.1.12. That snapshot also carried KnoxIDF identity work, including RFC 8693 token-exchange constants
in `gateway-util-common` (`KnoxIDFConstants`) and the JWT federation provider
(`JWTFederationFilter`). These are historical upstream observations from the 2026-10-03 design
snapshot, not claims about current Knox trunk.

A javax-built extension does not load in a Jakarta container. Aligning Aegis delegated identity with
KnoxIDF and running on Knox 3.0.0 javax are therefore two different Knox lines.

## Evidence at the decision date

- The extension's tests used the real Knox 3.0.0 SPI classes
  (`JerseyServiceDeploymentContributorBase`, `SubjectUtils`, principals) and the real Knox 3.0.0
  `AclsAuthorizationFilter`. Servlet request and context objects were supplied by the test; no servlet
  container or Jersey runtime was booted by the default build.
- Historical real-engine runs used `-Preal-engines` with `AEGIS_KNOX_HOME` pointing at an unpacked Knox
  3.0.0, installed the `knox-bundle` jar into Knox `ext/`, wrote a topology, started the Knox gateway
  process, and drove MCP calls through it. Retained run files are under `examples/adopters/`, but they
  are not candidate-bound evidence for the current dirty tree.
- The separate cold-install record under `docs/evidence/knox-3.0.0-cold-install/` used an earlier
  four-jar layout and is incomplete historical `recorded-unattested` evidence.

## Decision

1. Keep `aegis-knox3-extension` on Knox 3.0.0 javax as the released-Knox proof point. Claim only what
   candidate-bound evidence shows.
2. Target the Knox 3.1 Jakarta API for the next host extension, as a separate module, once Knox 3.1.0
   is released and its artifacts are published. Building against an unreleased snapshot is not a
   reproducible release input.
3. For the proposed Knox 3.1 path, map the delegated identity, actor chain, and any token established
   or exchanged by KnoxIDF into Aegis, with the mapping reviewed by Knox maintainers. This does not
   describe all Aegis hosts: the current own host supports configured RFC 8693 token exchange. The
   Knox 3.0 path does not bind a verified inbound subject token for that Aegis provider, so the
   provider fails closed there.
4. Keep the governance plane identical across both host modules. The host module stays thin: identity
   mapping, body bounding, and delegation to the shared `Gateway`.

## Consequences

- Two host modules may coexist for a period. Both depend on the same `aegis-config` gateway build.
- The 3.1 module needs its own real-container validation against a released Knox 3.1.0 before it is
  described as verified.
- Until then, documentation states that the extension targets Knox 3.0.0 and that Knox 3.1 needs a
  Jakarta build. Historical Knox 3.0 runs do not establish current-tree behavior.
