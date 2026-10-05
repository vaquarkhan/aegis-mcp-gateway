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

- Distribution: `knox-3.0.0.zip` from archive.apache.org. The SHA-512 matched the published
  `.sha512` (an integrity check, not a signature check).
- Aegis jars in `ext/`: engine, mcp, config, knox3-extension (2.0.0-SNAPSHOT, the earlier four-jar layout).
- `aegis-topology.xml`: the topology used (demo LDAP, Default identity assertion with
  `group.principal.mapping`, AclsAuthz).
- `aegis.json`: the Aegis config (the test MCP server's port differs per run).
- `requests.log`, `vrp.log`: raw HTTPS responses for K1 to K7 and V1 to V7.
- `aegis-audit.jsonl`: the Aegis hash-chained journal of the final run. It verifies OK, with 9 records.
- `knox-gateway-audit-excerpt.log`, `knox-gateway-log-excerpt.log`: Knox's own logs for the `aegis`
  topology.
- `aegis-console-excerpt.log`: Aegis catalog events and the undeploy close message, from all three runs.

Three runs:

1. The first run also restarted Knox mid-run. The journal continued across the restart (13 records
   verified).
2. The second run confirmed the undeploy log line.
3. The final run's outcomes for K1 to K7 and V1 to V7 match the first run.

Knox, its providers and the Aegis jars were real in these runs. The backend behind Aegis was a test MCP
server on loopback (`engine-mcp` in `aegis.json`), so this transcript covers the Knox integration path,
not an engine behind it. The requests and responses are preserved in the logs. The automated two-door
runs in [examples/adopters](../../../examples/adopters/README.md) cover real engines behind Knox.

## Provenance: which code this was captured against

These logs were captured against the jars as they stood **before** the extension-hook work and what
followed it. Since then the following changed in modules that Knox loads from `ext/`:

| Change | Module |
| --- | --- |
| `GovernanceHook` rewritten for all four operations, `HookRunner`, `HookBinding` | `aegis-engine` |
| `DecisionListener.Event` gained a `Span` component | `aegis-engine` |
| Built-in `remote` and `sql-readonly` hook types | `aegis-config` |
| `tracing.otlp` section | `aegis-config` |
| `OtlpSpanExporter`, now its own jar that `aegis-config` links against | `aegis-observability-otlp` (new) |
| `sources[].trust` and the `state` provider SPI | `aegis-config` |

`aegis-knox3-extension` itself was **not** changed apart from its bundle, which now also includes
`aegis-observability-otlp`. Its five tests pass against the current jars and the Knox 3.0.0 `provided`
artifacts on every build, which covers the Knox `Subject` bridge, the contributor registration, a
governed call under `Subject.doAs`, the 401 without a subject, and the route-local 503. That is
automated and current.

What is **not** current is this end-to-end transcript: the K1 to K7 and V1 to V7 responses were produced
by the earlier jars. The install is now five jars (see [KNOX-EXTENSION.md](../../KNOX-EXTENSION.md#install)),
so a re-capture must use that layout or the `knox-bundle` jar. This cold install should be re-captured
before any release. Tracked in [ROADMAP.md](../../ROADMAP.md).
