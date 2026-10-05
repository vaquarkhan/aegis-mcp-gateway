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
# aegis-knox3-extension

Optional Apache Knox 3.0 front door. Knox authenticates, asserts identity, and admits the route;
Aegis receives the Knox `Subject`, parses the MCP body, and applies the same governance engine as
own host. Knox APIs stay in `provided` scope so they are not shaded into the own-host jar.

## Developer instructions

```powershell
mvn -B -pl aegis-knox3-extension -am package
```

1. Install jars into Knox `ext/` as documented in [`docs/KNOX-EXTENSION.md`](../docs/KNOX-EXTENSION.md).
2. Do not add Knox types to `aegis-gateway` or `aegis-engine`.
3. Front-door scenarios live under `examples/adopters/` with `AEGIS_KNOX_HOME` set.

## Further reading

- [Knox extension guide](../docs/KNOX-EXTENSION.md)
- [Module reference](../docs/guide/modules.html#aegis-knox3-extension)
- [AGENTS.md](../AGENTS.md)
