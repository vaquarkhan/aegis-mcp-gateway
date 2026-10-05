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
# aegis-source-a2a

Agent-to-Agent (A2A) source plugin (`type: a2a`). One A2A 1.0 agent becomes one governed tool over
JSON-RPC `SendMessage`, with a same-origin rule for the agent card endpoint.

## Developer instructions

```powershell
mvn -B -pl aegis-source-a2a -am test
```

1. Card endpoint and message URL must pass egress; same-origin rules are fail-closed.
2. Profile example: [`adopters/configurations/a2a`](../adopters/configurations/a2a/).
3. Live front-door evidence uses the official `a2a-sdk` agent under `examples/adopters/a2a/`.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-source-a2a)
- [A2A configuration README](../adopters/configurations/a2a/README.md)
- [AGENTS.md](../AGENTS.md)
