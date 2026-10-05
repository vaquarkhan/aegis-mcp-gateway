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
# aegis-mcp-security

Own-host identity perimeter: JWT/JWKS, hashed static tokens, optional SPIFFE, OAuth protected-resource
metadata, route groups/scopes, and exact-origin CORS. Used by `aegis-gateway` before the MCP body is
governed.

## Developer instructions

```powershell
mvn -B -pl aegis-mcp-security -am test
```

1. Authentication failures must be 401; never fall open to anonymous MCP.
2. Keep JWT algorithm allow lists and audience/issuer checks fail-closed.
3. Route and scope checks run before large body buffering where the design requires it.
4. Document host-key changes in [`docs/CONFIGURATION.md`](../docs/CONFIGURATION.md).

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-mcp-security)
- [Security guide](../docs/guide/security.html)
- [AGENTS.md](../AGENTS.md)
