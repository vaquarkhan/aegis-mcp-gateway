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
# Evidence: Aegis 2.0 on stock Apache Knox 3.0.0

This directory retains incomplete historical evidence from an earlier Knox cold-install exercise. Its
classification is `recorded-unattested`, not `verified-current` or complete `verified-historical`
evidence. The exact capture date, candidate commit/tree, raw request and process logs, jar hashes, and
Knox archive SHA-512 value were not retained here.

## Files actually retained

- `README.md`: this historical description and its evidence limitations.
- `aegis-topology.xml`: the topology used, including demo LDAP, Default identity assertion with
  `group.principal.mapping`, and `AclsAuthz`.
- `aegis.json`: the Aegis configuration; the test MCP server's port differed per run.
- `aegis-audit.jsonl`: the Aegis hash-chained journal from the final run, containing 9 records.

No raw K1 to K7 or V1 to V7 request log, VRP log, Knox gateway log, Knox audit excerpt, or Aegis console
excerpt is retained in this directory. The prose below records observations but cannot substitute for
those missing artifacts.

## Historical setup and reported observations

- Distribution: `knox-3.0.0.zip` from archive.apache.org. The original operator recorded that its
  SHA-512 matched the published value, but the value and check output were not retained here.
- Aegis jars in `ext/`: engine, mcp, config, and knox3-extension 2.0.0-SNAPSHOT, using the earlier
  four-jar layout.
- The first run reportedly restarted Knox mid-run, with a 13-record journal continuing across restart.
- The second run reportedly confirmed the undeploy log line.
- The final run reportedly produced K1 to K7 and V1 to V7 outcomes matching the first run.
- The retained final audit journal was recorded as verifying successfully with 9 records.

Knox, its providers, and the Aegis jars were reported as real in these runs. The backend behind Aegis
was a test MCP server on loopback (`engine-mcp` in `aegis.json`), so the exercise concerned the Knox
integration path, not a real engine behind it. Automated adopter runs are described separately in
[examples/adopters](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/README.md).

## Provenance: which code this was captured against

The retained files came from jars as they stood **before** extension-hook work and later changes. The
following changes were subsequently recorded in modules that Knox loads from `ext/`:

| Change | Module |
| --- | --- |
| `GovernanceHook` rewritten for all four operations, `HookRunner`, `HookBinding` | `aegis-engine` |
| `DecisionListener.Event` gained a `Span` component | `aegis-engine` |
| Built-in `remote` and `sql-readonly` hook types | `aegis-config` |
| `tracing.otlp` section | `aegis-config` |
| `OtlpSpanExporter`, now its own jar that `aegis-config` links against | `aegis-observability-otlp` (new) |
| `sources[].trust` and the `state` provider SPI | `aegis-config` |

Knox integration, packaging, identity, and gateway code have changed further since this cold-install
capture. Current tests are executable assertions only; without retained candidate-bound output they do
not make this historical directory current evidence.

The current install uses five base jars or the `knox-bundle` jar; see
[KNOX-EXTENSION.md](../../KNOX-EXTENSION.md#install). A new cold-install capture must use that layout,
record exact commands and versions, and retain request, process, and verification output with a
candidate-bound SHA-256 manifest. This capture should be repeated before a release and is tracked in
[ROADMAP.md](../../ROADMAP.md).
