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
# aegis-mcp-guardrails

Optional project-owned governance hook (`hooks[].type: project-governance`). Use it when an Apache
(or other) project wants extra evidence or protected-resource checks on top of the engine chain.

## Developer instructions

```powershell
mvn -B -pl aegis-mcp-guardrails -am test
```

1. Hook failures in ENFORCE mode must deny; never fail open.
2. Keep project-specific policy out of typed sources; put it here or in `adopters/hooks`.
3. Reference profile: `adopters/profiles/apache-project-governance.example.yaml`.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-mcp-guardrails)
- [Adopter hooks](../adopters/hooks/README.md)
- [AGENTS.md](../AGENTS.md)
