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
# Security policy

Aegis sits between a language model and production data infrastructure, so a defect here is a
defect in someone's blast radius. Please treat security reports accordingly.

## Supported versions

| Version | Supported |
| --- | --- |
| 2.0.0-SNAPSHOT | Pre-release only. Reports are considered on a best-effort basis; no support commitment or published release exists. |
| 0.1.x | Archived source only. No active support commitment. |
| Older pre-releases | No |

## Reporting a vulnerability

Report privately. Do not open a public issue for a suspected vulnerability.

- Use the private report page at
  <https://github.com/vaquarkhan/aegis-mcp-gateway/security/advisories/new> when it is enabled for the
  repository. Include the affected version or commit, configuration with secrets removed, reproduction
  steps, and the impact you believe it has.
- If the page is unavailable, do not open a public issue. Use a private contact channel explicitly
  published by the repository owner. Before a public release, maintainers must verify that a private
  vulnerability-reporting channel works and publish a fallback channel.

The project targets acknowledgement within five business days and a status update at least every ten
business days while a report is being handled. The project asks reporters to allow up to 90 days before
public disclosure, unless both sides agree a shorter timeline. These are targets, not a guarantee while
Aegis is an unreleased, single-maintainer project.

## What happens after you report

1. **Triage target.** A maintainer attempts to reproduce the report against the latest commit and
   records the affected versions and a severity assessment, normally using CVSS 4.0.
2. **Private remediation.** When the advisory platform provides a private workspace, the fix is prepared
   there. Every fix should include a regression test that fails without it.
3. **Release and CVE request.** If a release carrying the fix is published, the project will publish the
   advisory with affected and fixed versions, workaround information where available, and credit. It
   will request a CVE when the advisory tooling and CVE eligibility permit it.
4. **Afterwards.** Published advisories appear at
   <https://github.com/vaquarkhan/aegis-mcp-gateway/security/advisories>, and a published release should
   note the fix in [CHANGELOG.md](CHANGELOG.md).

The project targets a fix within 30 days for critical and high severity reports and within 90 days for
medium and low severity reports. If a target cannot be met, the reporter should be told why and offered
a revised date.

## Lessons from other gateways

Bug classes published against other MCP gateways are checked here as regression tests, so the same
class cannot appear quietly: a schema `$ref` that the gateway would fetch (`SchemaRefTest`), a response
filter language that can read the gateway's environment (`JsonPointerExpressionTest`), an admin route
that registers a backend by URL (`AdminApiTest.noRouteRegistersABackendAtRunTime`), and injection or
URLs nested below the top-level argument (`GovernanceEngineTest`).

## Scope, properties and limits

[docs/SECURITY.md](docs/SECURITY.md) lists each security property Aegis promises with the test that
enforces it, what Aegis explicitly does not promise, the OWASP MCP Top 10 coverage, and the hardening
checklist. A report is in scope when it breaks one of those promises.

## Credit

Reporters are credited in [CHANGELOG.md](CHANGELOG.md) unless they ask not to be.
