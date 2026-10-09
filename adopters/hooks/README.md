<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Reference guardrail hooks

Three small services that speak the Aegis remote-hook contract (`type: remote` in `hooks:`). Each is a
single Python file on the standard library only, so you can read all of it before you trust it, and
copy it as the starting point for your own control in any language.

| Service | Phase | Data it needs | Refuses |
| --- | --- | --- | --- |
| [`pii_guard.py`](pii_guard.py) | before and after | `includeArguments`, `includeResult` | payment card numbers (issuer prefix and Luhn), IBANs (mod 97), US Social Security numbers |
| [`secrets_guard.py`](secrets_guard.py) | before | `includeArguments` | AWS access key ids, GitHub and Slack tokens, Google API keys, PEM private keys, JSON Web Tokens |
| [`change_window.py`](change_window.py) | before | nothing beyond the default request | `MUTATE` and `DESTRUCTIVE` calls outside the configured days and hours, or inside a freeze; reads and dry runs pass |

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
```

Start each service with the same token in `AEGIS_HOOK_TOKEN`:

```bash
python pii_guard.py --port 18301
python secrets_guard.py --port 18302
python change_window.py --port 18303 --days Mon,Tue,Wed,Thu,Fri --start 09:00 --end 17:00 --freeze 2026-12-20:2027-01-04
```

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
