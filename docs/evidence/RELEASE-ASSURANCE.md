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

This is local pre-release evidence from the working tree. It is not a published release, a signed
candidate, an external security certification, or an assertion that the working tree is a committed
candidate. The delivery-state decision is separate: the intended 2.0 source tree must be deliberately
tracked and reviewed by a human before publication.

## Fresh build

On 2026-10-04, `mvn -B -Prelease clean verify` completed with `BUILD SUCCESS` and no unapproved
RAT files. The release profile generated source JARs and CycloneDX JSON and XML SBOMs.

| Artifact | SHA-256 |
| --- | --- |
| `aegis-gateway-2.0.0-SNAPSHOT-all.jar` | `0d767572025a3d76c644fdadf2af844e046bb00432e7186d7f4a263fa935760a` |
| `aegis-gateway-2.0.0-SNAPSHOT-sources.jar` | `c704880342177405fb7826edc29a28d47e2673b50709c8ccf527d0ab00230f62` |
| `aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar` | `573e2ed6946102e06be002d67a90bd6f2c7fc7b2a4cf02008755fba0e4312cdf` |
| `aegis-knox3-extension-2.0.0-SNAPSHOT-sources.jar` | `42caa7fb6fe787b89711a7de2809a75939602b9cb7cb89e3c9c4f63c428eae4c` |
| aggregate `target/bom.json` | `4b4ebd3789d9a0f9ed3133ca765db85bdbcaf7855efb2938545a932f025fce44` |
| aggregate `target/bom.xml` | `5a8e8f9ac91a9adb67d6a3c3fa78f796effe0dc7e1377b66150b881b9e04eff0` |

The hashes are evidence for this local output only. Recreate and record them from an exact committed
candidate before a release decision.

## Credential and retained-evidence validation

A source-only Gitleaks 8.30.1 scan, with generated `target/` directories excluded in an in-memory
scanner configuration, found zero leaks after the final documentation refresh. The scan covered 6.06 MB
of source and documentation. It does not prove that runtime environments contain no secrets.

The `run-example` launchers were corrected to invoke the examples reactor that owns the
`real-engines` profile, build profile-generated plugin artifacts before the selected test, then clean
only the selected validation module. Fresh retained two-door evidence now records Burr, Pulsar, and Solr
as 2/2 tests, and Existing MCP Server as 3/3 tests, all with zero failures, errors, and skips. The
retained walkthroughs and generated IDE files use credential placeholders rather than demo credential
literals.

## Archive and legal-content inspection

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

## SBOM vulnerability scan

OSV Scanner 2.6.0 scanned the fresh artifact SBOMs with vulnerability data from the OSV service.
Exit status `1` means a finding, not a scanner failure.

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
| Local Knox comparator | Completed against local Apache Knox revision `7d4e4facc09318354d51bcee9b2f8d248dfca638`: 141 Aegis production Java files and 1,362 Knox Java files; 0 exact 60-token matches, 0 forbidden imports outside the extension, 0 packaged Knox classes. It reported 22 short conventional contributor-method line windows for review. |

No scanner proves authorship, ownership, originality, or license compliance. A completed external
whole-codebase source-match scan and a human review of every meaningful match remain release blockers.

## Real-engine validation

The supported local suite was run with the local Knox, Pulsar, Solr, A2A, Burr, MCP, and Node
prerequisites configured. The final run passed 32 tests with zero failures, errors, or skips. It includes
both own-host and stock Knox paths for the supported scenarios, the A2A agent path, and the Iceberg
OpenAPI-draft path.

During validation, two harness defects were fixed:

1. Knox front-door startup failures now close the newly-created runtime and descendants, rather than
   leaving an extension JAR locked on Windows.
2. The Pulsar fixture disables inactive-topic deletion in its isolated standalone configuration, so a
   topic remains available while the Knox path starts.

This run covers the local supported set only. It does not turn the 16 contract-only adapter profiles
into target-version compatibility claims. PostgreSQL, OpenBao/Vault, Keycloak token exchange, a live
OpenTelemetry collector, a Kubernetes installation, and a container-image run remain separate evidence
or release tasks where their exact candidate rerun is required.

## Removal review

No tracked code or Markdown file is an objectively safe deletion candidate. Tests, example servers,
adopter results, evidence, ADRs, legal material, comparison/history material, and tutorial tooling all
have a product, validation, legal, or release-evidence role. The only routine cleanup category is ignored
generated output under `target/`, which is not source material and is recreated by the build.

Do not delete files from the 2.0 working tree without a human-approved list, because the current tree
contains a deliberate but untracked replacement of the prior repository layout.

## Release blockers remaining

1. Establish the intended 2.0 Git baseline and stage/review it through a human-controlled process.
2. Resolve or explicitly accept the Derby gateway advisory.
3. Resolve or explicitly accept the Apache Knox 3.0.0 host-stack advisories for the supported Knox path.
4. Complete the external source-match review and retain raw results plus human dispositions.
5. Resolve the missing `jsp-api` license metadata and assemble a reviewed distribution-level LICENSE and NOTICE.
6. Complete the separate candidate evidence for PostgreSQL, OpenBao/Vault, Keycloak, OTLP, container image, and Kubernetes installation.
7. Complete the project release process, private reporting contacts, dependency/provenance review, signing, distribution, and candidate-repeatability decisions documented in [ROADMAP.md](../ROADMAP.md).
