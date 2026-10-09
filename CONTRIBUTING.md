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
# Contributing to Aegis MCP Gateway

Thanks for helping. Aegis is an Apache Incubator project. Contributions follow applicable Apache
Software Foundation and Incubator contribution, licensing, governance, and review processes, together
with the repository procedures documented here.

## Before you start

- Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the layers and trust boundaries and
  [docs/DEVELOPER.md](docs/DEVELOPER.md) for where each kind of change belongs.
- Open an issue before a large change so the design discussion happens before the code exists.
- By contributing, you agree that your contribution is made under the Apache License 2.0 and follow
  the applicable ASF/Incubator contributor-agreement and licensing process. Do not describe a local
  repository workflow as a substitute for that process.
- AI-assisted contributions follow the rules in [AGENTS.md](AGENTS.md).

## Development setup

Requirements: JDK 17 or newer, Maven 3.9 or newer. Run every command from the repository root.

```bash
mvn -B clean install                      # all modules, tests, license headers, coverage gates
mvn -B -Preal-engines clean install       # plus real Flink, Spark and Spark Connect
mvn -B -pl aegis-engine -am test          # one module while iterating
```

## Coding standards

- Java 17. Four space indent, 110 column soft limit, no tabs.
- UTF-8 without a byte order mark.
- Every source, build and documentation file carries the Apache License 2.0 header. The Apache RAT
  check in the build fails on a missing header.
- The engine, MCP and configuration modules have no third-party runtime dependency. Keep it that way.
- Comments explain intent, constraints and trade-offs. Do not narrate what the next line does.
- No em dashes in source, comments or documentation.

## Security-sensitive rules

The governance path has a few rules that are not negotiable, because breaking them turns a bug into
a bypass.

- Fail closed. A missing or ambiguous security setting is a startup error or a denial, never a
  default that grants access. Unknown configuration keys stop startup.
- Never log a token, a secret, a keystore password or an outbound `Authorization` header value.
- A source contributes capability only. Classification, resources, groups and previews come from the
  operator's configuration, never from the source.
- Anything that changes remote state is `MUTATE` or `DESTRUCTIVE`, never `READ`. An unclassified tool
  is `DESTRUCTIVE`.

## Tests

- Every behaviour change comes with a test. Bug fixes come with a test that fails before the fix.
- Name tests after the property under test, not the method under test.
- The default build reaches no external network. Real-engine tests live behind `-Preal-engines` and
  skip, never pass, when their target is not configured.
- Keep tests deterministic. No sleeps that race, no dependence on wall clock beyond the skew the
  code deliberately allows.

## Adding an integration

1. If the backend has an MCP server or a REST API, write configuration, not Java: a named folder under
   `adopters/configurations/adapters/<adopter>/` with `<adopter>.yaml` and a developer `README.md`.
2. If it needs a client library or a non-REST protocol, add a `ToolSourceFactory` module
   (see `aegis-source-jdbc` or `examples/aegis-spark-connect`).
3. Never add a configuration for an endpoint you have not confirmed in the product's documentation or
   source. Record evidence in [docs/VALIDATION.md](docs/VALIDATION.md).

## Commits and pull requests

- One logical change per pull request. Keep refactoring separate from behaviour changes.
- Write commit subjects in the imperative mood and explain the why in the body.
- State what changed, why, how it was tested, and whether any configuration key or default moved.
  A changed default is a breaking change and belongs in [CHANGELOG.md](CHANGELOG.md).

## Reporting security issues

Do not open a public issue. Follow [SECURITY.md](SECURITY.md).

## Code of conduct

Participation is governed by [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
