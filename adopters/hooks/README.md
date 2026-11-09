<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Reference guardrail hooks

Four small services that speak the Aegis remote-hook contract (`type: remote` in `hooks:`). The first
three are a single Python file on the standard library only, so you can read all of it before you trust
it, and copy it as the starting point for your own control in any language. The fourth,
[`bastion_guard.py`](bastion_guard.py), shows the delegate pattern: it calls MCP-Bastion's analyzers out
of process for the two model-based checks, and needs that separate package installed (see below).

| Service | Phase | Data it needs | Refuses |
| --- | --- | --- | --- |
| [`pii_guard.py`](pii_guard.py) | before and after | `includeArguments`, `includeResult` | payment card numbers (issuer prefix and Luhn), IBANs (mod 97), US Social Security numbers |
| [`secrets_guard.py`](secrets_guard.py) | before | `includeArguments` | AWS access key ids, GitHub and Slack tokens, Google API keys, PEM private keys, JSON Web Tokens |
| [`change_window.py`](change_window.py) | before | nothing beyond the default request | `MUTATE` and `DESTRUCTIVE` calls outside the configured days and hours, or inside a freeze; reads and dry runs pass |
| [`bastion_guard.py`](bastion_guard.py) | before and after | `includeArguments`, `includeResult` | prompt-injection text (MCP-Bastion PromptGuard) and personal data (MCP-Bastion Presidio); needs `mcp-bastion-python` installed |

[`hookserver.py`](hookserver.py) is the shared listener: bearer check, body limit, the `aegis: 2.0`
check, and the reply shape. A reason names where a value was found, never the value, because the
reason goes to the audit journal and back to the caller.

## How Aegis treats them

- In `mode: ENFORCE`, any answer other than HTTP 200 with a literal boolean `allow` denies the call
  (fail closed). A stopped service, a timeout and a malformed reply all deny.
- A service can only deny with `POLICY_DENIED`, `INVALID_INPUT` or `PROMPT_INJECTION`; anything else
  becomes `POLICY_DENIED`. It cannot rewrite a result, only withhold it.
- The URL must pass `egress` at startup. Arguments and results are sent only when you opt in, because
  the service is a third party from the data's point of view.
- A guard that was not given the data it checks denies instead of guessing.

## Configuration

```yaml
hooks:
  - type: remote
    mode: ENFORCE
    settings: {name: pii-guard, url: "http://127.0.0.1:18301/", tokenEnv: AEGIS_HOOK_TOKEN,
               phases: [BEFORE, AFTER], includeArguments: true, includeResult: true}
  - type: remote
    mode: ENFORCE
    settings: {name: secrets-guard, url: "http://127.0.0.1:18302/", tokenEnv: AEGIS_HOOK_TOKEN,
               phases: [BEFORE], includeArguments: true}
  - type: remote
    mode: ENFORCE
    settings: {name: change-window, url: "http://127.0.0.1:18303/", tokenEnv: AEGIS_HOOK_TOKEN, phases: [BEFORE]}
  - type: remote
    mode: ENFORCE
    settings: {name: bastion-guard, url: "http://127.0.0.1:18304/", tokenEnv: AEGIS_HOOK_TOKEN,
               phases: [BEFORE, AFTER], includeArguments: true, includeResult: true}
```

Start each service with the same token in `AEGIS_HOOK_TOKEN`:

```bash
python pii_guard.py --port 18301
python secrets_guard.py --port 18302
python change_window.py --port 18303 --days Mon,Tue,Wed,Thu,Fri --start 09:00 --end 17:00 --freeze 2026-12-20:2027-01-04
python bastion_guard.py --port 18304            # requires: pip install mcp-bastion-python[ml]
```

## Delegating to MCP-Bastion (`bastion_guard.py`)

MCP-Bastion is strong at two model-based checks that Aegis deliberately does not reimplement in Java:
prompt-injection classification (PromptGuard) and named-entity PII detection (Presidio). Rather than
rebuild them, `bastion_guard.py` runs them in a Python sidecar and Aegis consults it as a normal remote
hook. This keeps the two projects loosely coupled: Aegis stays Apache-2.0 and a single jar; the sidecar
is optional and installed by the operator.

- MCP-Bastion has its own license (Source Available, not Apache-2.0). This hook imports it only at
  runtime in a separate process, so nothing of it is bundled into the Aegis distribution. Install it
  yourself: `pip install mcp-bastion-python` (PromptGuard heuristics) or `[ml]` (adds Presidio PII).
- Keep stateful controls in Aegis, not the sidecar: approval and separation of duties, the hash-chained
  audit written before execution, catalog pinning and the signed catalog, scope, and rate and cost
  budgets. Splitting that state across a sidecar would weaken it.
- Use `bastion_guard` on the AFTER phase for PII and the BEFORE phase for injection; the hot allow and
  deny path stays native. Aegis already fails closed when a hook times out or is unreachable.
- For static, pre-deploy tool-catalog scanning, call MCP-Bastion's CLI (`mcp-bastion scan tools.json`)
  from CI or at catalog-admission time. That is a subprocess check, not a per-call one.

## Tests

- `python -m unittest test_hooks` (from this directory): the detectors, the window rules, and the
  listener's status codes over real HTTP.
- `python e2e_gateway.py --jar ../../aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar`: the
  gateway jar as a process, CPython's `http.server` as the backend, and these three services. It checks
  that a clean read passes, that each guard refuses before the backend sees the call (the backend log
  is read to prove it), that a result carrying a card number is withheld, that a stopped hook makes
  the gateway deny, and that the audit journal verifies. The CI workflow runs it on every push.

## Limits

These are examples, not a data-loss-prevention product. Pattern detectors miss data that is split,
encoded or described in words, and they can match test values. Use them to see how a control plugs
in, then replace the detection with the classifier or policy service your organisation trusts.
