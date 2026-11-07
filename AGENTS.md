<!-- SPDX-License-Identifier: Apache-2.0
     https://www.apache.org/licenses/LICENSE-2.0 -->

# AGENTS instructions

Repository instructions for humans and AI coding assistants working on Aegis (the MCP governance
gateway). Aegis is an Apache Incubator project developed partly with AI assistance; this file states
how to build, test, and contribute, and documents repository procedures for AI-assisted work. Contributors
must follow applicable Apache Software Foundation and Incubator policy. The AI-assisted development
disclosure is below. Companion files: [CONTRIBUTING.md](CONTRIBUTING.md) and
[docs/GOVERNANCE.md](docs/GOVERNANCE.md). Repository administrators may configure branch protection
to support these procedures; settings outside this checkout are not evidence of enforcement.

## Environment setup
- JDK 17 or newer (builds on JDK 21) and Maven 3.9 or newer.
- No other infrastructure is required for the default build; it reaches no external network.

## Commands
Run from the repository root.
- **Full build + tests:** `mvn -B clean install` (tests, Apache RAT license headers, coverage gates)
- **Real engines (opt-in):** `mvn -B -Preal-engines clean install`
- **One module (fast iterate):** `mvn -B -pl aegis-engine -am test`
- **Run (HTTP):** `java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config aegis.yaml`
  (add `--stdio` for stdio; see `README.md`)
- Always build and run the relevant tests before opening a PR; the suite must stay green.

## Repository structure
- `aegis-engine/` - the governance chain, controls, audit and state; JDK only.
- `aegis-mcp/` - MCP protocol for the 2025 and 2026-07-28 revisions, server handler, downstream clients.
- `aegis-config/` - strict configuration, declarative HTTP tools, plugin loading.
- `aegis-gateway/`, `aegis-mcp-security/` - the own-host front door and its identity perimeter.
- `aegis-knox3-extension/` - the optional Apache Knox 3.0 front door (Knox in `provided` scope only).
- `aegis-source-*`, `aegis-state-derby/`, `aegis-state-postgres/`, `aegis-mcp-guardrails/`,
  `aegis-observability-otlp/` - typed sources, durable and shared state, project guardrails and trace
  export. [docs/guide/modules.html](docs/guide/modules.html) describes every module.
- `adopters/` - named configuration profiles (`configurations/<profile>/`,
  `configurations/adapters/<adopter>/`), tutorials, clients and frameworks, each with a README.
- `examples/` - plugin sources, runnable bundles and opt-in real-engine validation.
- `Dockerfile`, `charts/aegis/` - the container image and the Helm chart (`charts/aegis/ci/` holds their checks).
- `docs/` - architecture, configuration, security, validation evidence and history.

## Architecture and conventions (do not break these)
- **Sources contribute capability only; all governance lives in the engine.** A source provides tool
  descriptors and executors, never policy. Classification, resources, groups and previews come from
  the operator's configuration.
- **`ToolClass`:** `READ`, `MUTATE` and `DESTRUCTIVE`. An unclassified tool is `DESTRUCTIVE`. `MUTATE`
  and `DESTRUCTIVE` need an independent per-call approval; `DESTRUCTIVE` additionally needs a fresh
  dry-run receipt bound to the same action.
- **Fail-closed:** when a control cannot run or a check is ambiguous, deny. Never fail open.
- **Stable deny codes:** reuse the deny taxonomy in `docs/DENY-CODES.md` (e.g. `POLICY_DENIED`,
  `APPROVAL_REQUIRED`, `EGRESS_DENIED`); do not invent wire codes without updating it.
- **Egress:** every HTTP destination, including URLs nested in arguments, passes the operator's
  `egress` allow list; everything else is denied.
- Keep the SPI, control order, deny codes, and config keys stable across changes.

## Coding standards
- Match existing style; keep changes small and reviewable.
- Arguments are validated by the engine's schema and typed-resource rules (they reject `..`,
  wildcards, empty values and control characters); a source must never build a query or command by
  concatenating an argument.
- Do not commit secrets, credentials, or proprietary data.
- Apache-2.0 header on every new source, build and documentation file (enforced by the RAT check).

## Testing standards
- JUnit 5. Add or update tests for every behavior change; a new test should fail without the change.
- Prefer deterministic tests; no network in the default build.
- For a new/changed destructive tool, assert its `ToolClass` and that it is denied without an
  approval and a dry-run receipt.

## AI-assisted development disclosure (provenance)

Parts of Aegis were developed with AI assistance **under contributor direction and review**. As an
Apache Incubator project, Aegis follows applicable ASF policy and the ASF generative-tooling guidance.
This disclosure gives reviewers and contributors the provenance context they need. Reference: ASF
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
  | Cursor | Pro (2026) | Maintainer-recorded assessment, 2026-08-11 | Cursor terms reviewed at https://cursor.com/terms-of-service. Contributors must independently check the terms that apply to their tool, plan and access path. This is not legal advice. |

- **Source-code matching is additional provenance evidence, not proof:** before the first release, run
  a scanner (e.g. SCANOSS - https://github.com/scanoss - or FOSSA - https://fossa.com/) over the
  codebase, focused on AI-assisted portions, to identify potential license-encumbered or third-party
  matches. Matching results need human investigation; a clean scan does not establish provenance.
  Status: not started (as of 2026-10-03); it is required before the first release
  ([release process items](docs/evidence/RELEASE-ASSURANCE.md#release-process-items)).
- **No fabricated numbers:** we do not claim a percent-AI figure (no reliable token-level boundary
  exists); we describe tools + review instead.
- **Commitment:** any code whose provenance or license cannot be established will be removed, replaced,
  or reimplemented before the first release; the provenance-review status will be reported in the
  release notes of the first release.

## AI-assisted contributions (transparency rules)
1. **Human accountability.** Whoever opens a pull request owns it: correctness, security, licensing,
   and quality, regardless of the tools used. "The AI wrote it" is not a defense.
2. **Disclose AI assistance** in the pull request as required by applicable ASF/Incubator process and
   repository procedure. The `Generated-by:` trailer is this repository's additional convention; it
   supplements, and does not replace, applicable ASF requirements.
3. **Human review is required.** The repository requires one approving review from an independent human
   maintainer in addition to applicable ASF/Incubator governance. AI review is an additional input,
   never a substitute for accountable human review or license-provenance review.
4. **Verification is model-independent:** build + tests + CI must pass.

## Who may commit: agents never commit
- **Every commit is made by a human maintainer** who reviewed the change and is accountable for the
  person named on the commit.
- **Agents/automation never commit** to the repository. They may only **propose** changes via a pull
  request; a human commits/merges.
- **Never give an agent your GitHub or other repository credentials.** Running an agent that commits "as you" is a
  policy violation, not a workflow choice.
- Do not squash to hide AI-assisted history; keep the `Generated-by:` trailer instead.
- Do not let a bot/agent appear as a commit author, and never count a bot toward committer or
  contributor diversity. Keep author identities clean (do not invent bot authors; this repo does not
  use `.mailmap` email collapsing).

## Commits and PRs
- Imperative-mood subject; explain the *why* in the body. Keep PRs focused.
- Do not push directly to `main`; open a PR (project policy requires an independent human approval +
  green CI; repository administrators should configure matching branch protection).
- Update `CHANGELOG.md` for user-visible changes.
- Do not rewrite pushed history (no force-push, amend, or rebase of shared commits).

## Boundaries
- **Ask first:** large cross-module refactors, new dependencies, changes to the SPI/deny taxonomy,
  anything touching auth/egress/approval semantics.
- **Never:** commit secrets; disable a fail-closed control to make a test pass; give an agent push
  credentials; use destructive git operations.

## References
- [CONTRIBUTING.md](CONTRIBUTING.md) · [docs/GOVERNANCE.md](docs/GOVERNANCE.md) · [docs/ROADMAP.md](docs/ROADMAP.md)
- [docs/COVERAGE-0.1-VS-2.0.md](docs/COVERAGE-0.1-VS-2.0.md) · [docs/HISTORY.md](docs/HISTORY.md)
- ASF generative-tooling guidance: https://www.apache.org/legal/generative-tooling.html
