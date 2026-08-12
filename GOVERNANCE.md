<!-- SPDX-License-Identifier: Apache-2.0
     https://www.apache.org/licenses/LICENSE-2.0 -->

# Governance

Aegis intends to follow the Apache Way. This document states how decisions are made, the roles, and the
contributor-to-committer path. It is intentionally short and will be aligned to the Apache Incubator
model if/when the project is accepted.

## Project status
Aegis is a young, initiated by single-author project that is seeking a neutral, community-based home. It does
not claim a mature or diverse community yet; growing one is the primary goal. Parts of the codebase were
developed with AI assistance under human direction and review - see the AI-assisted development
disclosure in [AGENTS.md](AGENTS.md).

## Roles
- **Contributor** - anyone who opens issues or pull requests. No prior status required.
- **Committer / maintainer** - has write access, reviews and merges PRs, and takes responsibility for
  the health of the code. Committership is earned through sustained, reviewed contribution (merit).
- **PPMC / PMC** (once incubating) - oversees releases, votes, and community growth.

## Decision-making
- **Code changes** require one approving review from a committer other than the author, plus green CI,
  before merge (enforced by the repository's GitHub branch protection settings).
- **Significant or contentious decisions** (roadmap direction, SPI/deny-taxonomy changes, adding
  committers, releases) are decided by public discussion and, where needed, a vote.
- **Public by default.** Once incubating, project- and release-level decisions happen on the public
  development mailing list (`dev@`); GitHub issues and PRs are for implementation, not a substitute for
  the list.

## Commits and reviews
- Every commit is made by a **human committer** who reviewed it and stands behind it. **Agents/automation
  never commit** and are never given credentials - they may only propose changes via PR (see
  [AGENTS.md](AGENTS.md)).
- No direct pushes to `main`; no rewriting of pushed history.

## Becoming a committer
Consistent, high-quality contributions - code, reviews, docs, adapters, issue triage - build the merit
that leads to an invitation. Adapter-sized work is a deliberate on-ramp for new contributors. Diversity
of contributors and affiliations is an explicit goal and a graduation criterion.

## Code of conduct
All participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md) (the ASF Code of Conduct).

## References
- [CONTRIBUTING.md](CONTRIBUTING.md) · [AGENTS.md](AGENTS.md)
- [ROADMAP.md](ROADMAP.md) · [SECURITY.md](SECURITY.md)
