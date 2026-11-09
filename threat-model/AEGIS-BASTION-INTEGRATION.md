# Aegis and MCP-Bastion: agreed integration

Date: 2026-11-07. This records what we agreed about whether Aegis should call MCP-Bastion, and what
was built.

## The question

Can Aegis internally call MCP-Bastion to be more secure, or must we rebuild Bastion's features in
Aegis?

## The agreed answer

Call it, do not rebuild it, for the two model-based checks only. Keep everything Aegis is already
strong at native. Integrate loosely, out of process, and optional.

### Why calling, not embedding

- Runtime boundary. Aegis is a JVM gateway; MCP-Bastion is a Python library. Aegis cannot load it
  in-process, so any use is out of process (an HTTP sidecar or a CLI subprocess).
- Aegis already has the seam. The remote-hook SPI (`type: remote`, BEFORE and AFTER phases, fail-closed
  timeouts) is exactly the per-call delegation point. No new mechanism was needed.
- License. MCP-Bastion is Source Available, not Apache-2.0. A loose, out-of-process call keeps the two
  apart; bundling or embedding it would mix licenses. The sidecar stays optional and operator-installed.

## What was agreed, item by item

| Capability | Decision | Reason |
| --- | --- | --- |
| Prompt-injection classification (PromptGuard) | DELEGATE to Bastion via remote hook | Model-based and Python-bound; rebuilding an ML classifier in Java is wasteful |
| PII detection and redaction (Presidio) | DELEGATE to Bastion via remote hook | Named-entity PII is model-based; Aegis keeps its own pattern scanner as the always-on baseline |
| Static tool-catalog scan (poisoned or drifted tools) | DELEGATE to Bastion CLI (`mcp-bastion scan`) at admission or in CI | Subprocess, pre-deploy; complements the signed catalog, not a per-call check |
| Independent approval and separation of duties | KEEP NATIVE | Core Aegis control; stateful |
| Hash-chained audit written before execution | KEEP NATIVE | Fail-closed evidence must not depend on a sidecar |
| Signed catalog, tool pinning, break-glass, artifact admission | KEEP NATIVE | Change control for the governed clusters |
| Identity, scope, rate limits and budgets | KEEP NATIVE | Stateful per-session; splitting state would create races and weaken limits |
| FinOps output-offload and discovery filter | BUILD NATIVE only if wanted | Not model-bound; do not route session state through a sidecar |

## What was built

- `adopters/hooks/bastion_guard.py`: a reference remote-hook sidecar. It speaks the Aegis remote-hook
  contract (shared `hookserver.py`) and calls MCP-Bastion's real analyzers:
  - injection: `mcp_bastion.pillars.prompt_guard.PromptGuardEngine.is_malicious(text)`
  - PII: `mcp_bastion.pillars.pii_redaction.PIIRedactor.detect_spans(text)`
  - BEFORE phase checks arguments (needs `includeArguments: true`); AFTER phase checks results
    (needs `includeResult: true`). A reason names where and the kind, never the value. If an enabled
    engine cannot be built, the service refuses to start, so it never silently fails open.
- `adopters/hooks/README.md`: a new row, the `hooks:` config entry, the start command, and a
  "Delegating to MCP-Bastion" section with the license and install note.

## How to use it

```yaml
hooks:
  - type: remote
    mode: ENFORCE
    settings: {name: bastion-guard, url: "http://127.0.0.1:18304/", tokenEnv: AEGIS_HOOK_TOKEN,
               phases: [BEFORE, AFTER], includeArguments: true, includeResult: true}
```

```bash
pip install mcp-bastion-python[ml]
python adopters/hooks/bastion_guard.py --port 18304
```

## Constraints and honesty

- The sidecar adds a Python process with models loaded; that trades Aegis's single-jar simplicity for
  the delegated checks, and adds per-call latency versus an in-process control. Use it on the AFTER
  phase for PII and BEFORE for injection, and keep the hot path native.
- The hook is written against MCP-Bastion's current API as read from its source on this host. It was
  not run here, because `mcp-bastion-python` is not installed. The operator installs it and runs the
  sidecar; Aegis fails closed if it is unreachable.
- MCP-Bastion's own figures (injection block rate, PII coverage) come from its project, not from an
  Aegis test. Treat the delegated checks as that project's controls, consulted by Aegis.
