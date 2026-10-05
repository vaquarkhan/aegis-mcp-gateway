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
# aegis-observability-otlp

Optional OTLP/HTTP JSON span exporter for governance decisions. Enabled only when
`tracing.otlp.endpoint` is set and passes egress at startup. Observer failures never change a
decision.

## Developer instructions

```powershell
mvn -B -pl aegis-observability-otlp -am test
```

1. Spans must not export caller identity, arguments, or results.
2. A full queue drops spans; never block the request path on the collector.
3. Endpoint must be egress-checked like every other outbound URL.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-observability-otlp)
- [Configuration](../docs/CONFIGURATION.md)
- [AGENTS.md](../AGENTS.md)
