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
# aegis-state-postgres

Shared PostgreSQL state provider (`state.type: postgres`) for multi-replica approvals, receipts, and
rate/budget windows. Required when the Helm chart sets `replicaCount > 1` (`sharedState: true`).

## Developer instructions

```powershell
mvn -B -pl aegis-state-postgres -am test
# Opt-in real PostgreSQL (see docs/evidence/POSTGRES-*.md for env vars)
mvn -B -pl aegis-state-postgres -am test -Ppostgres-integration
```

1. Approvals and rate windows must be one namespace across gateway JVMs.
2. Credentials come from environment / secret providers; never hard-code DSNs in tests committed with
   secrets.
3. Helm chart refuses multiple replicas unless `sharedState` is true and config uses this store.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-state-postgres)
- [Helm chart](../charts/aegis/README.md)
- [PostgreSQL evidence](../docs/evidence/POSTGRES-RDS-VALIDATION.md)
- [AGENTS.md](../AGENTS.md)
