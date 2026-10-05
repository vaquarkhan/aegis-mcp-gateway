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
# aegis-engine

The governance core. Every MCP tool call that Aegis allows or denies is decided here, in a fixed
fail-closed order. The module is JDK-only at runtime: no third-party libraries.

## What it owns

- `GovernanceEngine.call()` and the control chain (exposure, input/scope, policy, egress, rate,
  breaker, screening/hooks, approval, dry-run receipt, audit intent, execute, output guard).
- Tool catalog pinning, signed approval and dry-run tokens, hash-chained audit journal.
- SPI used by sources, hooks, policy engines, and state stores.
- Bounded JSON parsing and in-memory state.

## Developer instructions

```powershell
mvn -B -pl aegis-engine -am test
```

Rules for changes:

1. Never reorder or skip controls to make a test pass; fail closed on ambiguity.
2. Reuse deny codes from [`docs/DENY-CODES.md`](../docs/DENY-CODES.md); do not invent wire codes
   quietly.
3. Unclassified tools are `DESTRUCTIVE`. Assert class + approval/receipt behavior for new
   destructive paths.
4. Keep this module free of third-party runtime dependencies.
5. Update [`docs/guide/modules.html`](../docs/guide/modules.html#aegis-engine) and security docs when
   a control promise changes.

## Further reading

- [Security properties S1–S31](../docs/SECURITY.md)
- [Threat model](../docs/THREAT-MODEL.md)
- [Module reference](../docs/guide/modules.html#aegis-engine)
- [AGENTS.md](../AGENTS.md)
