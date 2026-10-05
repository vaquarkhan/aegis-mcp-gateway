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
# aegis-state-derby

Durable local state store (`state.type: derby-local`) for single-process approvals, nonces, and rate
windows. Suitable for one gateway replica; not for shared multi-pod approvals.

## Developer instructions

```powershell
mvn -B -pl aegis-state-derby -am test
```

1. When the store is full, refuse new approvals rather than silently dropping older entries.
2. Shutdown must report a stale lock clearly; do not leave the database half-open.
3. For multi-replica deployments use `aegis-state-postgres` instead.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-state-derby)
- [Configuration](../docs/CONFIGURATION.md)
- [AGENTS.md](../AGENTS.md)
