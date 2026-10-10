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
# Release-assurance evidence

This record is the 2026-10-04 local release-profile build. Later distribution work is on the [roadmap](../ROADMAP.md#next-release-candidate-evidence).

## Build record

On 2026-10-04, `mvn -B -Prelease clean verify` completed with `BUILD SUCCESS` and no unapproved RAT files. The release profile generated source JARs and CycloneDX JSON and XML SBOMs.

| Artifact | SHA-256 |
| --- | --- |
| `aegis-gateway-2.0.0-SNAPSHOT-all.jar` | `0d767572025a3d76c644fdadf2af844e046bb00432e7186d7f4a263fa935760a` |
| `aegis-gateway-2.0.0-SNAPSHOT-sources.jar` | `c704880342177405fb7826edc29a28d47e2673b50709c8ccf527d0ab00230f62` |
| `aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar` | `573e2ed6946102e06be002d67a90bd6f2c7fc7b2a4cf02008755fba0e4312cdf` |
| `aegis-knox3-extension-2.0.0-SNAPSHOT-sources.jar` | `42caa7fb6fe787b89711a7de2809a75939602b9cb7cb89e3c9c4f63c428eae4c` |
| aggregate `target/bom.json` | `4b4ebd3789d9a0f9ed3133ca765db85bdbcaf7855efb2938545a932f025fce44` |
| aggregate `target/bom.xml` | `5a8e8f9ac91a9adb67d6a3c3fa78f796effe0dc7e1377b66150b881b9e04eff0` |

The hashes identify the 2026-10-04 local outputs. The Knox bundle hash
`573e2ed6946102e06be002d67a90bd6f2c7fc7b2a4cf02008755fba0e4312cdf` belongs to this build. The
2026-10-05 bundle in [E2E-VALIDATION-2026-10-05.md](E2E-VALIDATION-2026-10-05.md)
has SHA-256 `0726C4CE71D23856F8FE9A58BE2C80E7FC7E4AE740ED70E8ECC8CBFB2617B780`. Repeating the build on the release candidate is on the [roadmap](../ROADMAP.md#next-release-candidate-evidence).

## Recorded credential and retained-evidence observations

A source-only Gitleaks 8.30.1 scan, with generated `target/` directories excluded, reported zero leaks over 6.06 MB of source and documentation.

The `run-example` launchers invoke the examples reactor that owns the `real-engines` profile, build profile-generated plugin artifacts before the selected test, then clean only the selected validation module. Retained two-door files record Burr, Pulsar, and Solr as 2/2 tests, and Existing MCP Server as 3/3 tests, all with zero failures, errors, and skips. The retained walkthroughs and generated IDE files use credential placeholders.

## Archive and legal-content inspection

The 2026-10-04 archive inspection:

| Check | Result |
| --- | --- |
| Gateway `all` JAR | 2,802 entries. No Knox, servlet, JAX-RS, Jersey, or Jackson classes. Four service-provider entries. Apache `META-INF/LICENSE`, Derby notice, and OnGres license files are present. |
| Knox bundle | 803 entries. No Knox, servlet, JAX-RS, Jersey, or Jackson classes. Knox service-contributor and state-provider service files are present. OnGres license files are present. |
| Gateway artifact SBOM | 12 components, all with declared license metadata. |
| Knox bundle SBOM | 156 components. `javax.servlet.jsp:jsp-api:2.1` is a Knox-provided dependency. Distribution LICENSE and NOTICE work is on the [roadmap](../ROADMAP.md#next-release-candidate-evidence). |
| Root `LICENSE` and `NOTICE` | Present. RAT accepts the source tree. The root notice names bundled and runtime dependencies and states Knox is provided. |

## SBOM scan

OSV Scanner 2.6.0 scanned the 2026-10-04 artifact SBOMs. The disposition of these advisories is on the [roadmap](../ROADMAP.md#next-release-candidate-evidence).

| Artifact | Components | Packages with advisories | Advisories |
| --- | ---: | ---: | ---: |
| Gateway `all` artifact SBOM | 12 | 1 | 1 |
| Knox bundle SBOM | 156 | 15 | 36 |
| A2A plugin SBOM | 4 | 0 | 0 |

The gateway finding is `org.apache.derby:derby:10.16.1.1` (`GHSA-rcjc-c4pj-xxrp`). The Knox bundle SBOM records the Apache Knox 3.0.0 host stack, including Avro, Bouncy Castle, Guava, Jackson, Jetty, Log4j API, Netty, and ZooKeeper. Knox 3.1 is on the [roadmap](../ROADMAP.md#next-apache-knox-host).

## Source-match review

| Check | Result |
| --- | --- |
| Local SCANOSS fingerprint, version 1.54.2 | 16,139 fingerprint lines, SHA-256 `d783bdde6206cd61b830d63030e94ffc2861538c88ec3dc780cfe03cc3bf9431`. License headers, `target`, `.git`, and `node_modules` were omitted. |
| External source comparison | Scheduled on the [roadmap](../ROADMAP.md#next-release-candidate-evidence). |
| Local Knox comparator | The [2026-09-29 report](KNOX-PROVENANCE.md) records 0 exact 60-token matches, 0 forbidden imports outside the extension, and 0 packaged Knox classes. |

## Real-engine record

A 2026-10-04 local suite with Knox, Pulsar, Solr, A2A, Burr, MCP, and Node reported 32 tests with zero failures, errors, or skips, including own-host and stock Knox paths, the A2A agent path, and the Iceberg OpenAPI-draft path. The release-candidate repeat, and the PostgreSQL, OpenBao, Vault, Keycloak, collector, image, and Kubernetes runs, are on the [roadmap](../ROADMAP.md#next-release-candidate-evidence).

## Release process items

Work owned by the release process, upstream Knox or Derby, or the operator's publish steps rather than by
gateway code. Product work is in the [roadmap](../ROADMAP.md).

| Item | Owner and gate | Where evidence lives |
| --- | --- | --- |
| ASF source-header transition | Applied only after the ASF records the applicable Software Grant; until then the current author headers stay | `tools/asf-header-switch.py` (a guarded transition helper; Apache RAT then checks headers) |
| Source-match and provenance scan (SCANOSS or FOSSA) | Release assurance and the AI-provenance commitment in `AGENTS.md` | [source-match review](#source-match-review) |
| SBOM vulnerability gate | A chosen vulnerability feed and CI policy; the SBOM already builds with `-Prelease` | root `pom.xml` `release` profile |
| Derby and Knox host-stack advisories | Upstream upgrade or a reviewed risk acceptance | this document |
| LICENSE and NOTICE distribution review | Legal review of shaded and provided metadata | [archive and legal-content inspection](#archive-and-legal-content-inspection) |
| Security and conduct private channel proof | Maintainer and GitHub organisation configuration | root `SECURITY.md`, `CODE_OF_CONDUCT.md` |
| Real-engine suite always on in CI | CI runner provisioning | [VALIDATION.md](../VALIDATION.md) |
| Manual Knox cold-install transcript refresh | Operator documentation, recaptured with the current bundle | [knox cold-install note](knox-3.0.0-cold-install/README.md) |
