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
# Apache CouchDB: profile supported, real-engine evidence not yet captured

## Canonical reference

[`aegis/canonical.yaml`](aegis/canonical.yaml) is an exact reference copy of the
[canonical adapter profile](../../../adopters/configurations/adapters/couchdb/couchdb.yaml), not
configuration captured from a real engine run. Current profile/contract coverage is
`AdapterConfigsTest` in [`examples/aegis-examples`](../../aegis-examples); real results remain not
recorded.

Aegis loads and governs [`couchdb.yaml`](../../../adopters/configurations/adapters/couchdb/couchdb.yaml).
Its deterministic contract test covers database list/detail, create and destructive delete request shapes and
the required governance controls.

A real two-door run needs a CouchDB node with a disposable database. The source is declarative HTTP, so the
standard Knox setup is only the Aegis extension and topology; no CouchDB plugin jar is needed. Use the canonical
YAML, substitute the CouchDB URL, egress allowlist, credentials and groups, then run the same scenario once
through own-host Aegis and once through Knox. CouchDB is not installed in the [tested environment](../../../docs/VALIDATION.md#tested-environments) and Docker/WSL is
unavailable, so no false end-to-end evidence is present.
