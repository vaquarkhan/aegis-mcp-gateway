# Incubation readiness (additive checklist)

Author: Viquar Khan.

Additive docs and process items for the Aegis incubation vote. This checklist does **not** change
source under review. Branch protection is configured in GitHub repository settings (not in-tree).

## Present in-repo

| Item | Purpose | Status |
| --- | --- | --- |
| [AGENTS.md](../AGENTS.md) | AI-assisted development disclosure, agents-never-commit, Generated-by trailer, contribution rules | Present; fill the per-tool terms table |
| [GOVERNANCE.md](../GOVERNANCE.md) | Roles, decision-making, committer path, human-only commits | Present |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Contribution and review norms | Present |
| [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) | Conduct expectations | Present |
| [ROADMAP.md](../ROADMAP.md) | Honest deferred work including bootstrap / IP clearance | Present |
| CI workflow | Maven build on PRs (`.github/workflows/maven.yml`) | Present |

## Intentionally skipped

| Item | Note |
| --- | --- |
| `.mailmap` | **Not used.** This project does not collapse or rewrite author emails. External contributors (PJ Fanning, xleoken) stay as their own identities. |

## GitHub settings (record; configure in the browser)

Settings → Rules → Rulesets → Active ruleset targeting the default branch:

- Require a pull request: 1 approval, dismiss stale approvals, require approval of the most recent
  push, require conversation resolution
- Require status checks (strict) + the real CI check name
- Require signed commits (set up local signing first)
- Require linear history; block force pushes; restrict deletions
- Bypass list: Repository admin (logged)

Also: Settings → Actions → General → Workflow permissions → read-only; keep repo write access to
humans only.

## Proposal wording (edit when sending / updating the proposal)

- Independence target: committers from N independent organizations before graduation.
- Project and release decisions happen on `dev@`; GitHub is implementation only.
- Provenance-review status reported in the first podling report.

## Manual follow-ups (no code churn)

1. Fill the per-tool terms table in [AGENTS.md](../AGENTS.md).
2. Run SCANOSS or FOSSA and record status in AGENTS.md (not started / in progress / complete).
3. Confirm branch protection in the GitHub UI matches the record above.
4. Apply the three proposal wording bullets to the public proposal / list email as needed.
