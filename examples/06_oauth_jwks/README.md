# HTTP OAuth (JWKS)

Author: Viquar Khan.

Resource-server mode: the gateway validates inbound JWTs against a JWKS URL.

## Env

```bash
export MCP_GW_TRANSPORT=http
export MCP_GW_HTTP_HOST=127.0.0.1
export MCP_GW_HTTP_PORT=8090
export MCP_GW_AUTH_MODE=oauth
export MCP_GW_OAUTH_ISSUER=https://issuer.example.com
export MCP_GW_OAUTH_AUDIENCE=aegis
export MCP_GW_OAUTH_JWKS_URL=https://issuer.example.com/.well-known/jwks.json
export MCP_GW_OAUTH_REQUIRED_SCOPE=mcp.read
export MCP_GW_ADAPTERS=flink
export FLINK_REST_URL=http://localhost:8081
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

Without `MCP_GW_OAUTH_JWKS_URL` the filter denies every request (fail-closed).

Present `Authorization: Bearer <access_token>` where the JWT `iss` / `aud` / `exp` / scopes match
the config above.
