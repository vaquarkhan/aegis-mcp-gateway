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
# Governance

"Governance" means two things for this project, and this page covers both:

1. **How Aegis participates in Apache Incubator governance:** applicable Apache Software Foundation
   and Incubator policy, supplemented by the repository procedures linked below.
2. **What governance Aegis provides** to the teams that deploy it: the controls an operator gets
   over what AI agents can do with their systems.

## Part 1: Apache Incubator project governance

Aegis is an Apache Incubator project. Applicable ASF and Incubator governance, contribution, conduct,
licensing, release, security, and voting processes take precedence over repository procedures. The
repository procedures in [AGENTS.md](../AGENTS.md) and [CONTRIBUTING.md](../CONTRIBUTING.md) support
that governance but do not replace it. In particular:

- **Merit and openness.** Significant decisions should be discussed through the applicable public Apache
  and project channels and recorded in the project history.
- **Review before merge.** This repository requires an approving independent human review and passing CI
  as an additional procedure. Repository administrators should configure matching branch protection.
- **Humans commit.** Agents and automation never commit and never hold credentials; they may propose
  changes for accountable human review. AI assistance is disclosed and provenance scanning remains
  required before the first release.
- **Stable contracts.** The deny codes and step numbers ([docs/DENY-CODES.md](DENY-CODES.md)), the SPI
  in `aegis-engine/.../spi`, and the configuration keys ([docs/CONFIGURATION.md](CONFIGURATION.md)) are
  public contracts. Changing one needs public review first; the two 2.0 code additions
  (`CATALOG_INTEGRITY`, `AUDIT_UNAVAILABLE`) are marked as proposals for exactly that reason.
- **Vendor neutrality.** The core has no third-party runtime dependency and no vendor-specific code
  path; engine-specific knowledge lives in configuration files and optional plugin modules.
- **How 2.0 changes are reviewed.** Follow [CONTRIBUTING.md](../CONTRIBUTING.md), keep each review
  focused and evidence-backed, and use the applicable ASF/Incubator process for decisions and approval.

## Part 2: what governance Aegis gives an operator

Every control below is configuration, applies to every backend in the same way, and fails closed.

[MCP governance guideline](MCP-GOVERNANCE-GUIDELINE.md) explains how an operator applies these controls as a complete runtime baseline. It is Aegis guidance, not an Apache Software Foundation policy, release procedure, compliance certification, or replacement for a project's own governance.

| Question an operator asks | Control | Where |
| --- | --- | --- |
| Who is calling, on whose behalf? | Host identity (JWT/JWKS, Knox Subject), effective subject, delegation chain, issuer | ARCHITECTURE.md section 3 |
| What may they even see? | Per-group exposure of tools, resources and prompts; write tools hidden from readers | `access`, `allowedGroups` |
| What does each tool do? | Operator classification READ / MUTATE / DESTRUCTIVE; unclassified means DESTRUCTIVE; upstream hints only raise | `tools.<name>.class` |
| On which data? | Typed resources extracted from arguments by JSON pointer (`spark:table:sales.orders`), never widened to `*` | `resources`, `scopes` |
| Is it allowed? | Rules with deny-overrides and default deny, composable with OPA | `policy` |
| Should a human agree first? | Per-call approval bound to the exact action, single use, separation of duties | `approval`, admin API |
| What would it do? | Dry run (gateway, backend-native validate, or a preview tool) with a signed receipt required before a destructive run | `vrp`, `dryRun` |
| Where may it reach? | Egress allow list over every destination and DNS answer; metadata ranges blocked | `egress` |
| How much? | Per-caller and per-tool rates, weighted hourly budgets, per-backend circuit breakers | `rateLimits`, `breaker` |
| Can the tool surface change under us? | Pinned tool and prompt definitions; changes and poisoned text quarantined until accepted | `catalog` |
| What reaches the model? | Output bounds, secret redaction, content screening, binary refused unless allowed | `output`, `screening` |
| What happened, provably? | Intent journaled before execution; hash-chained, verifiable offline; W3C trace ids | `audit`, `--verify-audit` |
| Is it working? | Prometheus decision metrics by tool, source and outcome | `metrics` |

Governance state never lives in an MCP session, so the same controls hold for the stateful 2025
protocol and the stateless 2026-07-28 protocol, and for both front doors (the gateway's own server and Knox).

What Aegis does not decide: whether an agent's *intent* is good. It governs actions, data flow and
accountability; it limits prompt injection but does not claim to prevent it (see
[SECURITY.md](SECURITY.md#what-aegis-does-not-promise)).

### Which of these controls answer a regulatory question

[GOVERNANCE-MAPPING.md](GOVERNANCE-MAPPING.md) takes the same controls and maps them to the EU AI Act,
GDPR, DORA and NIS2, NIST AI RMF, SOC 2, ISO/IEC 42001 and 27001, the OWASP LLM and Agentic top tens,
and a pointer table for other jurisdictions. Each control there names the code that implements it and
a test that fails if it stops working. It is a control mapping to help an audit, not a compliance
claim: Aegis has no certification, attestation or conformity assessment, and that page says so first.
Read its gap table before repeating any coverage statement from it.
