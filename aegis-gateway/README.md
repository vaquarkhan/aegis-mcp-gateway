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
# aegis-gateway

Own-host front door: JDK HTTP(S) and stdio MCP endpoint, shaded runnable jar. Host identity and
route admission come from `aegis-mcp-security`; governance comes from the engine.

## What it owns

- `Main` entry, `--config`, `--stdio`, audit verify and catalog-manifest CLIs.
- Listener binding, health/readiness/metrics routes, graceful shutdown.
- Packaging of the `*-all.jar` used by tutorials and the container image.

## Developer instructions

```powershell
mvn -B -pl aegis-gateway -am package
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config path/to/aegis.yaml
```

1. Keep transport and identity concerns out of `aegis-engine`.
2. Do not embed Knox classes in this jar (`KnoxPackagingBoundaryTest` guards that).
3. After listener or CLI changes, update [`docs/CONFIGURATION.md`](../docs/CONFIGURATION.md) and the
   developer guide.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-gateway)
- [Handbook](../docs/guide/handbook.html)
- [AGENTS.md](../AGENTS.md)
