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
# aegis-config

Turns one JSON or YAML document into a running governed gateway: engine, MCP handler, admin API,
catalog, metrics, tracer, and state. This is the module operators and most plugin authors touch
through configuration keys, not by editing Java.

**Dependencies:** `aegis-engine`, `aegis-mcp`, `aegis-observability-otlp`. SnakeYAML is optional and
used only for `.yaml` / `.yml` files.

## What it owns

- Strict configuration loading (unknown keys stop startup; enumerated values are closed lists).
- Built-in `http` and `mcp` sources, `remote` and `sql-readonly` hooks, OPA policy wiring.
- Static and RFC 8693 token-exchange credentials; `env` / `file` / `vault` secret providers.
- ServiceLoader factories: `ToolSourceFactory`, `GovernanceHookFactory`, `StateStoreFactory`,
  `SecretProviderFactory`.
- `--openapi-draft` helper that emits a reviewable deny-all draft from an OpenAPI document.

## Developer instructions

```powershell
# Build and unit-test this module and its dependencies
mvn -B -pl aegis-config -am test

# Opt-in Vault/OpenBao secret integration (needs AEGIS_VAULT_ADDR and AEGIS_VAULT_TOKEN)
mvn -B -pl aegis-config -am test -Pvault-integration
```

When you change behavior:

1. Prefer a configuration key documented in [`docs/CONFIGURATION.md`](../docs/CONFIGURATION.md).
2. Keep fail-closed: missing secrets, bad URLs, or unknown keys must refuse startup.
3. Add or update a test under `src/test/java` that fails without your change.
4. Do not put policy logic in a source plugin; sources contribute capability only.
5. Update [`docs/guide/modules.html`](../docs/guide/modules.html#aegis-config) if a public key or
   control changes.

## Where to look in the source

| Concern | Package / entry |
| --- | --- |
| Load + validate YAML/JSON | `io.github.vaquarkhan.aegis.config` |
| Declarative HTTP tools | `…config.http` |
| Credentials / token exchange | `…config.credentials` |
| Hooks | `…config.hooks` |
| Secrets | `…config.secrets` |
| OPA | `…config.policy` |
| OpenAPI draft | `…config.openapi` |

## Further reading

- [Configuration reference](../docs/CONFIGURATION.md)
- [Developer guide](../docs/guide/developer.html)
- [Module reference (HTML)](../docs/guide/modules.html#aegis-config)
- [AGENTS.md](../AGENTS.md) — architecture boundaries for AI-assisted and human contributors
