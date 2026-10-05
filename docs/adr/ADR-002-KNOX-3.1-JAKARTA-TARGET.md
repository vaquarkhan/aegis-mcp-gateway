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

Knox trunk (`3.1.0-SNAPSHOT`) has moved to the Jakarta namespace: its root `pom.xml` sets
`jakarta.servlet-api` 6.0.0, `jakarta.ws.rs-api` 3.1.0 and Jersey 3.1.12. Knox trunk also carries the
KnoxIDF identity work, including RFC 8693 token exchange constants in
`gateway-util-common` (`KnoxIDFConstants`) and the JWT federation provider (`JWTFederationFilter`).

A javax-built extension does not load in a Jakarta container. Aligning Aegis delegated identity with
KnoxIDF and running on Knox 3.0.0 javax are therefore two different Knox lines.

## Evidence today

- The extension's tests run the real Knox 3.0.0 SPI classes (`JerseyServiceDeploymentContributorBase`,
  `SubjectUtils`, principals) and the real Knox 3.0.0 `AclsAuthorizationFilter`. Servlet request and
  context objects are supplied by the test; no servlet container or Jersey runtime is booted by the
  default build.
- The real-engine validation suite (`-Preal-engines` with `AEGIS_KNOX_HOME` pointing at an unpacked
  Knox 3.0.0) installs the `knox-bundle` jar into Knox `ext/`, writes a topology, starts the real Knox
  gateway process and drives MCP calls through it. Retained runs are under `examples/adopters/`.
- A separate cold-install transcript (`docs/evidence/knox-3.0.0-cold-install/`) was captured on an
  earlier four-jar layout and is historical.

## Decision

1. Keep `aegis-knox3-extension` on Knox 3.0.0 javax as the released-Knox proof point. Claim only what
   the evidence above shows.
2. Target the Knox 3.1 Jakarta API for the next host extension, as a separate module, once Knox 3.1.0
   is released and its artifacts are published. Building against an unreleased snapshot is not a
   reproducible release input.
3. Map KnoxIDF delegated identity (actor chain and exchanged tokens) into the Aegis caller identity in
   that module, with the mapping reviewed by Knox maintainers. Aegis itself does not perform token
   exchange; Knox does.
4. Keep the governance plane identical across both host modules. The host module stays thin: identity
   mapping, body bounding and delegation to the shared `Gateway`.

## Consequences

- Two host modules may coexist for a period. Both depend on the same `aegis-config` gateway build.
- The 3.1 module needs its own real-container validation against a released Knox 3.1.0 before it is
  described as verified.
- Until then, documentation states that the extension runs on Knox 3.0.0 and that Knox 3.1 needs a
  Jakarta build.
