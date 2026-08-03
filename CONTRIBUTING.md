# Contributing to Aegis MCP Gateway

Author: Viquar Khan.

Thanks for helping. This project follows Apache Software Foundation conventions: Apache License 2.0
for everything, review before merge, and a preference for boring, auditable code in the governance
path.

## Before you start

- Read [docs/DESIGN-apache-mcp-gateway.md](docs/DESIGN-apache-mcp-gateway.md) for the architecture
  and [docs/LLD-apache-mcp-gateway.md](docs/LLD-apache-mcp-gateway.md) for the interceptor chain,
  deny taxonomy and configuration keys.
- Open an issue before a large change so the design discussion happens before the code exists.
- By contributing you agree that your contribution is licensed under the Apache License 2.0.

## Development setup

Requirements: JDK 17 or newer, Maven 3.9 or newer.

```bash
mvn -q clean install
mvn -q -pl mcp-gateway-core -am test
```

## Coding standards

- Java 17. Four space indent, 110 column soft limit, no tabs.
- UTF-8 without a byte order mark. LF or CRLF are both fine; do not mix them inside one file.
- Every source file carries the Apache license header used by the existing files, and every public
  type carries a Javadoc comment ending with `@author`.
- Never write to `System.out`. On the stdio transport stdout carries MCP JSON-RPC frames and one
  stray `println` corrupts the session. Log through SLF4J, which is wired to stderr.
- Comments explain intent, constraints and trade-offs. Do not narrate what the next line does.
- No em dashes in source, comments or documentation.

## Security-sensitive rules

The governance path has a few rules that are not negotiable, because breaking them turns a bug into
a bypass.

- Fail closed. A missing or ambiguous security setting is a startup error or a denial, never a
  default that grants access.
- Never log a token, a secret, a keystore password or an outbound `Authorization` header value.
- Do not widen a caller identity anywhere except where the credential that produced it is verified.
- A new tool declares a `ToolClass`. Anything that changes remote state is `MUTATE` or
  `DESTRUCTIVE`, never `READ`, even when it looks harmless.

## Tests

- Every behaviour change comes with a test. Bug fixes come with a test that fails before the fix.
- Name tests after the property under test, not the method under test. `deniesExpiredTokens` beats
  `testValidate2`.
- Tests must not reach the public network. Use a loopback `HttpServer` the way
  `OAuthResourceFilterTest` and `CimdVerifierTest` do.
- Keep tests deterministic. No sleeps that race, no dependence on wall clock beyond the skew the
  code deliberately allows.

## Adding an engine adapter

1. Create a `mcp-adapter-<engine>` module that depends on `mcp-gateway-core`.
2. Implement `EngineAdapter` and declare it in
   `META-INF/services/io.github.vaquarkhan.aegis.core.spi.EngineAdapter`.
3. Describe the tools in `src/main/resources/adapters/<engine>/tools.yaml` with a `class` for each.
4. Read engine settings through `GatewayConfig.adapterProperty`, supporting both a dotted YAML key
   and a conventional environment variable.
5. Add adapter tests that exercise the tool manifest and the failure paths of the backend client.

See [docs/adapters.md](docs/adapters.md) for the existing adapters and their settings.

## Commits and pull requests

- One logical change per pull request. Keep refactoring separate from behaviour changes.
- Write commit subjects in the imperative mood: `add JWKS validation to the OAuth filter`.
- Explain the why in the body. The diff already shows the what.
- Pull requests should state what changed, why, how it was tested, and whether any configuration
  key or default moved. A changed default is a breaking change and belongs in
  [CHANGELOG.md](CHANGELOG.md).

## Reporting security issues

Do not open a public issue. Follow [SECURITY.md](SECURITY.md).

## Code of conduct

Participation is governed by [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
