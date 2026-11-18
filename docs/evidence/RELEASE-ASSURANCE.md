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

### Current binary packaging checks

Issue [#31](https://github.com/vaquarkhan/aegis-mcp-gateway/issues/31) refers to the removed 0.1
`mcp-gateway-dist` module. The current shaded artifacts are `aegis-gateway` (`all`),
`aegis-knox3-extension` (`knox-bundle`), `examples/aegis-gateway-kafka` (`all`) and
`examples/aegis-gateway-spark` (`all`).

The build-only [aegis-build-tools plugin](../../aegis-build-tools/pom.xml) reads the resolved runtime
JARs selected by each bundle's Shade `artifactSet`, not the aggregate SBOM or the provided/test
classpath. During `prepare-package` it writes binary-specific legal files under `target/binary-legal`.
Shade's `IncludeResourceTransformer` installs those files as `META-INF/LICENSE` and `META-INF/NOTICE`.
Colliding top-level upstream legal resources are filtered only after their contents have been collected;
nested license files remain in the archive. Signature files are excluded and service-provider merging
is unchanged. No `ApacheLicenseResourceTransformer` discards non-Apache terms, and no notice transformer
rewrites upstream paragraphs.

The LICENSE starts with Aegis's Apache-2.0 text and identifies the exact third-party runtime coordinates,
license expressions and package names, followed by full upstream license texts. The NOTICE starts with
Aegis's source attribution and appends upstream notices verbatim, including their original line endings.
The [version-pinned component review](../../tools/binary-legal/components.txt) supplies missing legal
texts and notes for upstream-shaded/native components. [Supplement sources](../../tools/binary-legal/sources.txt)
record their origin; these files are legal texts, not copied implementation code. Builds do not download
licenses. A new runtime version or missing supplement fails the build until its review is updated.

| Bundle | Third-party runtime content |
| --- | --- |
| Stock gateway | SnakeYAML 2.4; Derby/derbyshared 10.16.1.1; pgJDBC 42.7.12, including its OnGres components |
| Knox bundle | pgJDBC 42.7.12 and its OnGres components; the Knox host stack is provided, not bundled |
| Kafka example | Stock gateway content plus Kafka clients 3.9.1, zstd-jni 1.5.6-4, lz4-java 1.8.0, snappy-java 1.1.10.5 and SLF4J 1.7.36 |
| Spark example | Stock gateway content plus Spark Connect 4.0.1 and its resolved Scala, Jackson, Log4j, ICU, Kryo and other runtime dependencies; the exact list is in the generated LICENSE |

Jetty, Logback, Jakarta Servlet, Nimbus JOSE and the MCP Java SDK mentioned by the original issue
are not in these distributions; no license election for them is needed. SLF4J is MIT, pgJDBC/OnGres
are BSD-2-Clause, and native Zstandard uses its BSD option. Snappy also carries Bitshuffle's MIT
license and the GCC runtime exception reported by its upstream NOTICE. The Spark client is already
shaded upstream: [its embedded-component record](../../tools/binary-legal/spark-connect.txt) documents
the original and relocated namespaces and supplements lost Arrow, Netty, gRPC and other legal texts.
Do not mistake the Checker Framework compiler's GPL license for the MIT-licensed `checker-qual`
annotations that Spark embeds.

Release-candidate command (from the repository root):

```powershell
mvn -B -Prelease clean verify
```

The plugin is built in the same reactor. To build one bundle, keep `-am` (for example
`mvn -B -pl aegis-gateway -am verify`), or install `aegis-build-tools` first.
The plugin locates the reviewed inventory in an ancestor of the Maven invocation root, so
the examples reactor used by both `run-example` launchers is supported. CI verifies both
example bundles through `mvn -B -f examples/pom.xml -Preal-engines -pl aegis-gateway-kafka,aegis-gateway-spark -am -DskipTests verify`
after installing the root reactor.

The ordinary `verify` phase, including existing CI jobs, checks the actual shaded archive: exactly one
LICENSE and NOTICE, byte-for-byte equality with freshly assembled legal content, no additional root or
immediate `META-INF` LICENSE/NOTICE variants, no stale signatures, and all selected third-party class
entries present. Nested component licenses remain allowed. Unit tests exercise legal-resource collisions,
variant names, nested licenses, unknown versions, missing texts, overwritten notices and incorrect
archive inventories. Source headers, normal tests, coverage and static analysis remain enabled.

This is a packaging check, not a legal opinion or proof of source provenance. A source-header search
found no copied third-party implementation identified by an upstream copyright/license header; it
cannot establish the origin of unmarked or AI-assisted code. The source-match and human provenance
review below remains a first-release gate. Do not change source copyright attribution without the
recorded grant, and do not interpret the historical inspection below as evidence for a new candidate.

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
