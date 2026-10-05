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
# aegis-source-zookeeper

Typed Apache ZooKeeper tool source (`type: zookeeper`). Path arguments are validated as typed
resources; the plugin must not treat them as free-form shell or script input.

## Developer instructions

```powershell
mvn -B -pl aegis-source-zookeeper -am test
```

1. Keep path validation strict (no `..`, wildcards, or control characters from callers).
2. Destructive tools need approval + dry-run receipt like every other DESTRUCTIVE tool.
3. Real two-door evidence: [`examples/adopters/zookeeper`](../examples/adopters/zookeeper/).

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-source-zookeeper)
- [ZooKeeper tutorial](../docs/tutorials/zookeeper.md)
- [AGENTS.md](../AGENTS.md)
