<!-- SPDX-License-Identifier: Apache-2.0
     https://www.apache.org/licenses/LICENSE-2.0 -->

# AGENTS instructions

Repository instructions for humans and AI coding assistants working on Aegis (the MCP governance
gateway). Aegis is developed partly with AI assistance; this file states how to build, test, and
contribute, and the rules that keep AI-assisted work transparent and Apache-compliant. The AI-assisted
development disclosure is in this file (below). Companion files: [CONTRIBUTING.md](CONTRIBUTING.md)
and [GOVERNANCE.md](GOVERNANCE.md). Branch protection is configured in the GitHub repository settings.

## Environment setup
- JDK 17 or newer (builds on JDK 21) and Maven 3.9 or newer.
- No other infrastructure is required to build or run the core - it is a no-infrastructure library.

## Commands
- **Full build + tests:** `mvn -q clean install`
- **Core module only (fast iterate):** `mvn -q -f mcp-gateway-core/pom.xml clean test`
- **Run (stdio):** `java -jar mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar` (see `README.md`)
- Always build and run the relevant tests before opening a PR; the suite must stay green.

## Repository structure
- `mcp-gateway-core/` - SPI, the 10-step governance interceptor chain, auth, config, transports, bootstrap.
- `mcp-adapter-<engine>/` - one module per engine (Flink, Kafka, Spark, Iceberg are reference-depth; ~29
  others are thin HTTP adapters). Adapters contribute capability only.
- `mcp-gateway-dist/` - shaded runnable distribution bundling every adapter.
- `docs/` - design, LLD, design-conformance matrix, adapters, operations.

## Architecture and conventions (do not break these)
- **Adapters contribute capability only; all governance lives in the core.** An adapter provides tool
  definitions, resources, egress hosts, and backend calls - never policy.
- **`ToolClass`:** `READ` (registered by default), `MUTATE` and `DESTRUCTIVE` (require write unlock + an
  `approvalToken`; DESTRUCTIVE additionally needs a VRP dry-run receipt). Every MUTATE/DESTRUCTIVE tool
  must list `approvalToken` in its schema `required[]`.
- **Fail-closed:** when a control cannot run or a check is ambiguous, deny. Never fail open.
- **Stable deny codes:** reuse the existing deny taxonomy (e.g. `POLICY_DENIED`, `APPROVAL_REQUIRED`,
  `EGRESS_DENIED`); do not invent wire codes without checking the registry.
- **Egress:** adapters declare `egressAllowHosts`; the gateway unions these with the operator allow list
  and denies everything else.
- Keep the SPI, interceptor order, deny codes, and config keys stable across changes.

## Coding standards
- Match existing style; keep changes small and reviewable.
- Validate all tool arguments through the shared `Inputs` helpers (reject `..`, control chars, etc.).
- Do not commit secrets, credentials, or proprietary data.
- Apache-2.0 header on new source files (enforced at donation).

## Testing standards
- JUnit 5. Add or update tests for every behavior change; a new test should fail without the change.
- Prefer deterministic tests; no network in the gated path.
- For a new/changed destructive tool, assert its `ToolClass` and that `approvalToken` is required.

## AI-assisted development disclosure (provenance)

Parts of Aegis were developed with AI assistance **under the author's direction and review**. We
disclose this in the repo so reviewers and contributors do not have to discover it. Reference: ASF
generative-tooling guidance - https://www.apache.org/legal/generative-tooling.html

- **Tool used:** **Cursor** (underlying provider LLMs accessed through it).
- **Where:** the initial 0.1.0 bootstrap import was AI-assisted; later implementation,
  refactoring, tests, and docs used AI assistance to varying degrees.
  This is learning from https://github.com/vaquarkhan/flink-mcp-enterprise-server and https://github.com/vaquarkhan/kafka-mcp-enterprise-server
  work and defining single repo with adopter to avoid multiple implementations
- **Per-tool terms check (condition 1):** confirm the tool's terms of use place no restrictions on the
  output inconsistent with the Open Source Definition - a per-tool contributor responsibility.

  | Tool | Version / plan | Output-terms checked vs OSD? | Notes |
  | --- | --- | --- | --- |
  | Cursor | &lt;fill&gt; | &lt;yes/no + date&gt; | &lt;link to reviewed Cursor terms&gt; |

- **Code scanning is the provenance mechanism (not review):** before the first Apache release, run a
  scanner (e.g. SCANOSS - https://github.com/scanoss - or FOSSA - https://fossa.com/) over the codebase,
  focused on AI-assisted portions, to detect license-encumbered or third-party matches. Status:
  &lt;not started / in progress / complete&gt;.
- **No fabricated numbers:** we do not claim a percent-AI figure (no reliable token-level boundary
  exists); we describe tools + review instead.
- **Commitment:** any code whose provenance or license cannot be established will be removed, replaced,
  or reimplemented before an Apache release; the provenance-review status will be reported in the first
  podling report.

## AI-assisted contributions (transparency rules)
1. **Human accountability.** Whoever opens a PR owns it - correctness, security, licensing, quality -
   regardless of the tools used. "The AI wrote it" is not a defense.
2. **Disclose AI assistance** in the PR (which tool) and add a `Generated-by:` trailer to AI-assisted
   commits. Do not fabricate a percent-AI figure.
3. **Human review is required.** Every change needs one approving review from a human committer other
   than the author. **AI review (any tool) is an extra layer, never the required human review**, and it
   does not establish license provenance (see the code-scanning requirement in the disclosure above).
4. **Verification is model-independent:** build + tests + CI must pass.

## Who may commit - agents never commit (Apache Infra policy)
- **Every commit is made by a human committer** who reviewed the change and carries the ICLA
  representations attached to the person named on the commit.
- **Agents/automation never commit** to the repository. They may only **propose** changes via a pull
  request; a human commits/merges.
- **Never give an agent your ASF or GitHub credentials.** Running an agent that commits "as you" is a
  policy violation, not a workflow choice.
- Do not squash to hide AI-assisted history; keep the `Generated-by:` trailer instead.
- Do not let a bot/agent appear as a commit author, and never count a bot toward committer or
  contributor diversity. Keep author identities clean (do not invent bot authors; this repo does not
  use `.mailmap` email collapsing).

## Commits and PRs
- Imperative-mood subject; explain the *why* in the body. Keep PRs focused.
- Do not push directly to `main`; open a PR (branch policy requires one approval + green CI).
- Update `CHANGELOG.md` for user-visible changes.
- Do not rewrite pushed history (no force-push, amend, or rebase of shared commits).

## Boundaries
- **Ask first:** large cross-module refactors, new dependencies, changes to the SPI/deny taxonomy,
  anything touching auth/egress/approval semantics.
- **Never:** commit secrets; disable a fail-closed control to make a test pass; give an agent push
  credentials; use destructive git operations.

## References
- [CONTRIBUTING.md](CONTRIBUTING.md) · [GOVERNANCE.md](GOVERNANCE.md) · [ROADMAP.md](ROADMAP.md)
- [docs/DESIGN-CONFORMANCE-0.1.md](docs/DESIGN-CONFORMANCE-0.1.md)
- ASF generative-tooling guidance: https://www.apache.org/legal/generative-tooling.html
