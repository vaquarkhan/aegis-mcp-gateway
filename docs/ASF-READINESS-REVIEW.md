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
# ASF and Incubator conformance review

- Review date: 2026-10-04
- Scope: repository documentation, policies, build metadata, release profile, notices, Pages HTML,
  adopters, tutorials, and workflow in this checkout.
- Status: internal technical review against public ASF materials. It is not ASF legal advice, an
  official Incubator assessment, or a Board determination.

Aegis is an Apache Incubator project. Applicable Apache Software Foundation and Incubator policy governs
project conduct, contributions, licensing, governance, releases, security, and use of Apache marks.
Repository procedures may add technical safeguards, but must not override or be represented as replacing
applicable ASF or Incubator process.

## Primary sources

This review paraphrases the following public sources. They are the source of truth where they apply.

| Area | Public source | Review consequence for this repository |
| --- | --- | --- |
| Governance and roles | [How the ASF works](https://www.apache.org/foundation/how-it-works.html) | Project documentation and decisions must follow the applicable Incubator governance model and public-process expectations. |
| Releases | [ASF release policy](https://www.apache.org/dev/release.html) | Release preparation must use the applicable ASF/Incubator process and review the actual candidate artifacts. |
| Third-party licensing | [ASF third-party licensing policy](https://www.apache.org/legal/resolved.html) | Release-time review must cover the assembled distribution, dependency licenses, and notices. A source-tree NOTICE is not that review. |
| Trademarks | [ASF trademark policy](https://www.apache.org/foundation/marks/) | Use Apache names and marks under applicable ASF policy. |
| Conduct | [ASF Code of Conduct](https://www.apache.org/foundation/policies/conduct.html) | The ASF reporting and enforcement process applies; project-local contacts may supplement but not replace it. |
| Generative tooling | [ASF generative-tooling guidance](https://apache.org/legal/generative-tooling.html) | Contributors remain responsible for rights, third-party material, review, and disclosure. Tool terms and source matching do not establish provenance by themselves. |

## Corrections made in this review

| Area | Correction | Evidence |
| --- | --- | --- |
| Project status | Removed statements that Aegis is independent, non-ASF, or only voluntarily aligned with ASF policy. | [AGENTS.md](../AGENTS.md), [CONTRIBUTING.md](../CONTRIBUTING.md), [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md), and [GOVERNANCE.md](GOVERNANCE.md) |
| Governance | Distinguished applicable ASF/Incubator governance from the runtime governance Aegis provides to deployments. | [GOVERNANCE.md](GOVERNANCE.md) |
| AI assistance | Retained provenance controls and the repository `Generated-by:` convention, while describing the convention as supplemental to applicable ASF/Incubator process. | [AGENTS.md](../AGENTS.md) |
| Conduct | Replaced the independent-project conduct framing with the applicable ASF Code of Conduct reporting and enforcement path. | [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) |
| Release roadmap | Reframed ASF/Incubator work as current project obligations rather than a hypothetical future transition. | [ROADMAP.md](ROADMAP.md) |
| Token exchange | Retained the correction that a verified caller JWT goes only to the configured, egress-checked token endpoint when exchange is configured, never to the protected backend, audit, hooks, or policy input. | [Keycloak evidence](evidence/TOKEN-EXCHANGE-KEYCLOAK.md) |
| A2A | Retained the correction that Aegis is an A2A 1.0 JSON-RPC client/tool source, with no A2A front door, streaming, task continuation, or card-signature verification. | [A2A ADR](adr/ADR-001-A2A-AS-TOOL-SOURCE.md) |
| Claims evidence | Refreshed Knox bundle hash, validation boundaries, and a 2026-10-05 end-to-end record; expanded 0.1 versus 2.0 coverage with a per-engine side-by-side table. | [E2E validation](evidence/E2E-VALIDATION-2026-10-05.md), [COVERAGE-0.1-VS-2.0.md](COVERAGE-0.1-VS-2.0.md), [DEFECT-LOG.md](DEFECT-LOG.md) D30–D31 |
| Security threat model | Added product threat model (STRIDE, abuse scenarios) and focused S1–S31 control-suite evidence; fixed oversized-body JDK client abort assertion (D32). | [THREAT-MODEL.md](THREAT-MODEL.md), [SECURITY-VALIDATION.md](evidence/SECURITY-VALIDATION.md), [DEFECT-LOG.md](DEFECT-LOG.md) D32 |

## Current release and compliance work

1. **Provenance review:** complete an external source-code matching scan, investigate results, and retain
   the review record. A scanner is evidence, not proof of complete provenance.
2. **Dependency and legal review:** resolve or explicitly accept the fresh SBOM findings and review the
   actual distributed LICENSE, NOTICE, dependency licenses, and artifact contents.
3. **Release process:** follow the applicable ASF/Incubator candidate, approval, signing, distribution,
   security-fix, and recordkeeping process.
4. **Evidence freshness:** repeat retained target runs on the exact release candidate wherever a
   compatibility claim will be made. Keep contract-only profiles labelled as contract-only.
5. **Operational packaging:** CI builds and starts the image; a local 2026-10-05 run also passed Helm
   lint, kubeconform, and `start_rendered.py` against the built gateway jar. No chart has been installed
   in a Kubernetes cluster and no release image has been published to a registry.
6. **Software grant and headers:** run the guarded header-transition helper only after the ASF has
   recorded the applicable software grant. Until then, do not claim that its output header applies.
