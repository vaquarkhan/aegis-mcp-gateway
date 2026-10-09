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

This document records historical, `recorded-unattested` local pre-release observations. It is not a
published release, a signed candidate, an external security certification, or evidence for the current
dirty working tree. No immutable raw command output, candidate commit/tree manifest, or complete
content-addressed evidence bundle was retained. The intended 2.0 source tree must be deliberately
tracked and reviewed by a human before publication.

## Historical build record

On 2026-10-04, `mvn -B -Prelease clean verify` was reported to complete with `BUILD SUCCESS` and no
unapproved RAT files. The release profile reportedly generated source JARs and CycloneDX JSON and XML
SBOMs. This is a `recorded-unattested` result.

| Artifact | SHA-256 |
| --- | --- |
| `aegis-gateway-2.0.0-SNAPSHOT-all.jar` | `0d767572025a3d76c644fdadf2af844e046bb00432e7186d7f4a263fa935760a` |
| `aegis-gateway-2.0.0-SNAPSHOT-sources.jar` | `c704880342177405fb7826edc29a28d47e2673b50709c8ccf527d0ab00230f62` |
| `aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar` | `573e2ed6946102e06be002d67a90bd6f2c7fc7b2a4cf02008755fba0e4312cdf` |
| `aegis-knox3-extension-2.0.0-SNAPSHOT-sources.jar` | `42caa7fb6fe787b89711a7de2809a75939602b9cb7cb89e3c9c4f63c428eae4c` |
| aggregate `target/bom.json` | `4b4ebd3789d9a0f9ed3133ca765db85bdbcaf7855efb2938545a932f025fce44` |
| aggregate `target/bom.xml` | `5a8e8f9ac91a9adb67d6a3c3fa78f796effe0dc7e1377b66150b881b9e04eff0` |

The hashes identify the dated 2026-10-04 local outputs only. In particular, the Knox bundle hash
`573e2ed6946102e06be002d67a90bd6f2c7fc7b2a4cf02008755fba0e4312cdf` belongs to this build. The
separate 2026-10-05 bundle recorded in [E2E-VALIDATION-2026-10-05.md](E2E-VALIDATION-2026-10-05.md)
has SHA-256 `0726C4CE71D23856F8FE9A58BE2C80E7FC7E4AE740ED70E8ECC8CBFB2617B780`. The differing hashes
represent separate dated artifacts, not conflicting measurements of one artifact. Recreate and record
all release hashes from an exact committed candidate before a release decision.

## Recorded credential and retained-evidence observations

A source-only Gitleaks 8.30.1 scan was reported with generated `target/` directories excluded through
an in-memory scanner configuration. It reported zero leaks over 6.06 MB of source and documentation.
The exact command and raw output were not retained, so this is `recorded-unattested`; it also does not
show that runtime environments contain no secrets.

The `run-example` launchers were corrected to invoke the examples reactor that owns the
`real-engines` profile, build profile-generated plugin artifacts before the selected test, then clean
only the selected validation module. Historical retained two-door files record Burr, Pulsar, and Solr
as 2/2 tests, and Existing MCP Server as 3/3 tests, all with zero failures, errors, and skips. Later
source changes mean these results are historical. The retained walkthroughs and generated IDE files
use credential placeholders rather than demo credential literals.

## Historical archive and legal-content inspection

The following 2026-10-04 observations are `recorded-unattested`; their exact commands, archive
listings, and raw output were not retained in a candidate-bound evidence bundle.

| Check | Result |
| --- | --- |
| Gateway `all` JAR | 2,802 entries. No Knox, servlet, JAX-RS, Jersey, or Jackson classes. Four service-provider entries. Apache `META-INF/LICENSE`, Derby notice, and OnGres license files are present. |
| Knox bundle | 803 entries. No Knox, servlet, JAX-RS, Jersey, or Jackson classes. Knox service-contributor and state-provider service files are present. OnGres license files are present. |
| Gateway artifact SBOM | 12 components, all with declared license metadata. |
| Knox bundle SBOM | 156 components. `javax.servlet.jsp:jsp-api:2.1` has no declared license metadata. It is a Knox-provided dependency, not a class in the bundle, but its metadata needs a human license disposition before release. |
| Root `LICENSE` and `NOTICE` | Present and RAT accepts the source tree. The root notice names bundled/runtime dependencies and states Knox is provided, not bundled. |

The Knox bundle's top-level `META-INF/LICENSE` comes from pgJDBC and the bundle has no top-level
`META-INF/NOTICE`. Before publishing a binary distribution, assemble and review a distribution-level
LICENSE and NOTICE that represents the product and every bundled dependency. Do not rely only on
shaded-JAR resource precedence.

## Historical SBOM vulnerability scan

OSV Scanner 2.6.0 was reported to scan the 2026-10-04 artifact SBOMs with vulnerability data from the
OSV service. The exact command and raw output were not retained, so the result is
`recorded-unattested`. Exit status `1` was interpreted as a finding, not a scanner failure.

| Artifact | Components | Vulnerable packages | Advisories | Status |
| --- | ---: | ---: | ---: | --- |
| Gateway `all` artifact SBOM | 12 | 1 | 1 | **Release blocker** |
| Knox bundle SBOM | 156 | 15 | 36 | **Supported-host blocker** |
| A2A plugin SBOM | 4 | 0 | 0 | No OSV finding |

### Gateway finding

`org.apache.derby:derby:10.16.1.1` is flagged by `GHSA-rcjc-c4pj-xxrp`.

OSV lists 10.16.1.2 as a fixed version, but that coordinate was unavailable from Maven Central during
this run. The next Derby line needs a newer Java baseline than the project's Java 17 baseline. Do not
claim the gateway artifact is vulnerability-clean until maintainers choose and validate one of these
paths: a supported patched Derby/JDK combination, an architectural change that stops bundling Derby,
or a documented and reviewed risk acceptance.

### Knox host findings

The Knox bundle does not package Knox classes, but its SBOM records the runtime host stack. The scan
reported 36 advisories across 15 dependencies supplied by Apache Knox 3.0.0, including Avro, Bouncy
Castle, Guava, Jackson, Jetty, Log4j API, Netty, and ZooKeeper. The Aegis bundle cannot repair those
by shading a different version into Knox. A supported-host decision is needed: upgrade to a Knox release
with remediated dependencies, complete the planned Jakarta/Knox 3.1 migration, or document an explicit
reviewed risk acceptance with deployment mitigations.

The aggregate reactor SBOM has additional findings from build, test, and helper dependencies. It is
useful for review but is not the artifact vulnerability result above.

## Source-match and provenance review

| Check | Result |
| --- | --- |
| Local SCANOSS fingerprint, version 1.54.2 | Completed: 16,139 obfuscated fingerprint lines, 345,138 bytes, SHA-256 `d783bdde6206cd61b830d63030e94ffc2861538c88ec3dc780cfe03cc3bf9431`. License headers, `target`, `.git`, and `node_modules` were omitted from the fingerprint input. |
| External SCANOSS source comparison | **Not completed.** The SCANOSS service returned HTTP 503 rate-limit responses and an approximately eight-hour retry indication. It produced an empty results file. Do not treat the local fingerprint as a source-match result. Retry after the service window or use an approved alternative with a retained raw result and human match dispositions. |
| Local Knox comparator | The preserved [2026-09-29 report](KNOX-PROVENANCE.md) covers Aegis revision `91c482e5b3256ced22398e820013ada182f959a3` with 122 production Java files and Apache Knox revision `7d4e4facc09318354d51bcee9b2f8d248dfca638` with 1,362 Java files. It records 0 exact 60-token matches, 0 forbidden imports outside the extension, 0 packaged Knox classes, and 22 short conventional contributor-method line windows for review. This historical report does not establish the current tree. |

No scanner proves authorship, ownership, originality, or license compliance. A completed external
whole-codebase source-match scan and a human review of every meaningful match remain release blockers.

## Historical real-engine record

A 2026-10-04 local suite was reported run with Knox, Pulsar, Solr, A2A, Burr, MCP, and Node
prerequisites configured. The exact selecting command, reactor output, and immutable per-test reports
were not retained. The reported result was 32 tests with zero failures, errors, or skips, including
own-host and stock Knox paths for supported scenarios, the A2A agent path, and the Iceberg OpenAPI-draft
path. This result is `recorded-unattested` and must not be used as current-tree attestation.

During that validation, two harness defects were recorded as fixed:

1. Knox front-door startup failures closed the newly-created runtime and descendants rather than
   leaving an extension JAR locked on Windows.
2. The Pulsar fixture disabled inactive-topic deletion in its isolated standalone configuration so a
   topic remained available while the Knox path started.

This historical run covered the then-supported local set only. It did not turn the **6** contract-only
adopter profiles into target-version compatibility claims. PostgreSQL, OpenBao/Vault, Keycloak token
exchange, a live OpenTelemetry collector, a Kubernetes installation, and a container-image run remain
separate evidence or release tasks requiring an exact candidate rerun.

## Removal boundary

Ignored generated output under `target/` is recreated by the build and is not source material. This
review did not establish any broader deletion conclusion. Any proposed source or documentation
removal requires a separately reviewed list and must preserve historical and release evidence.

## Release blockers remaining

1. Establish the intended 2.0 Git baseline and stage/review it through a human-controlled process.
2. Resolve or explicitly accept the Derby gateway advisory.
3. Resolve or explicitly accept the Apache Knox 3.0.0 host-stack advisories for the supported Knox path.
4. Complete the external source-match review and retain raw results plus human dispositions.
5. Resolve the missing `jsp-api` license metadata and assemble a reviewed distribution-level LICENSE and NOTICE.
6. Complete the separate candidate evidence for PostgreSQL, OpenBao/Vault, Keycloak, OTLP, container image, and Kubernetes installation.
7. Complete the project release process, private reporting contacts, dependency/provenance review, signing, distribution, and candidate-repeatability decisions listed under [release process items](#release-process-items).

## Release process items

Work owned by the release process, upstream Knox or Derby, or the operator's publish steps rather than by
gateway code. Product work is in the [roadmap](../ROADMAP.md).

| Item | Owner and gate | Where evidence lives |
| --- | --- | --- |
| ASF source-header transition | Applied only after the ASF records the applicable Software Grant; until then the current author headers stay | `tools/asf-header-switch.py` (a guarded transition helper; Apache RAT then checks headers) |
| Source-match and provenance scan (SCANOSS or FOSSA) | Release assurance and the AI-provenance commitment in `AGENTS.md`; needs an external service account | [source match and provenance review](#source-match-and-provenance-review) |
| SBOM vulnerability gate | A chosen vulnerability feed and CI policy; the SBOM already builds with `-Prelease` | root `pom.xml` `release` profile |
| Derby and Knox host-stack advisories | Upstream upgrade or a reviewed risk acceptance | this document |
| LICENSE and NOTICE distribution review | Legal review of shaded and provided metadata | [historical archive and legal content inspection](#historical-archive-and-legal-content-inspection) |
| Security and conduct private channel proof | Maintainer and GitHub organisation configuration | root `SECURITY.md`, `CODE_OF_CONDUCT.md` |
| Real-engine suite always on in CI | CI runner provisioning | [VALIDATION.md](../VALIDATION.md) |
| Manual Knox cold-install transcript refresh | Operator documentation, recaptured with the current bundle | [knox cold-install note](knox-3.0.0-cold-install/README.md) |
