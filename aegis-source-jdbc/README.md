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
# aegis-source-jdbc

Typed JDBC tool source (`type: jdbc`). Exposes governed metadata and SQL tools; the operator supplies
the JDBC driver. Classification and approval still come from configuration, not from this module.

## Developer instructions

```powershell
mvn -B -pl aegis-source-jdbc -am test
```

1. Never build SQL by concatenating caller arguments; use the shared splitter and parameters.
2. Statement-carrying READ tools need an honest credential and usually a `sql-readonly` hook.
3. Drivers are provided by the operator; do not shade proprietary drivers into the gateway.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-source-jdbc)
- [Configuration](../docs/CONFIGURATION.md)
- [AGENTS.md](../AGENTS.md)
