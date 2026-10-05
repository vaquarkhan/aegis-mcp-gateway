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
# aegis-mcp

MCP protocol layer for the 2025 and 2026-07-28 revisions: server handler, admin REST API, and
downstream HTTP/stdio MCP clients used by `type: mcp` sources. Depends on `aegis-engine` only; no
third-party runtime libraries.

## What it owns

- JSON-RPC / MCP method dispatch, origin checks, sessions (2025 era), header/body agreement
  (2026-07-28).
- Admin routes for approvals, catalog accept, and audit verify.
- Bounded outbound HTTP helper used by every egress-checked call.
- Downstream MCP proxy clients (Streamable HTTP and stdio).

## Developer instructions

```powershell
mvn -B -pl aegis-mcp -am test
```

1. Preserve protocol revision behavior; add tests for both eras when you touch framing.
2. Never follow redirects on outbound HTTP; keep response size and deadline bounds.
3. Admin routes stay group-scoped; CSRF Origin rules must remain fail-closed.
4. Document wire-visible deny or error code changes in [`docs/DENY-CODES.md`](../docs/DENY-CODES.md).

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-mcp)
- [Developer guide](../docs/guide/developer.html)
- [AGENTS.md](../AGENTS.md)
