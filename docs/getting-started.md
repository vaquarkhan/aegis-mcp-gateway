# Getting started

Author: Viquar Khan.

This page takes you from a clone to a running gateway that an MCP client can talk to. It assumes
JDK 17 or newer and Maven 3.9 or newer.

## 1. Build

```bash
git clone https://github.com/vaquarkhan/aegis-mcp-gateway.git
cd aegis-mcp-gateway
mvn -q clean install
```

The runnable artifact is `mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar`.

To iterate on the core module alone:

```bash
mvn -q -pl mcp-gateway-core -am test
```

## 2. First run over stdio

stdio is the right transport for a local agent such as Cursor or Claude Desktop. There is no
network listener and no inbound credential, because the client owns the process.

```bash
export MCP_GW_TRANSPORT=stdio
export MCP_GW_ADAPTERS=flink
export FLINK_REST_URL=http://localhost:8081
java -jar mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

The process speaks MCP JSON-RPC on stdout and logs to stderr. Nothing else may be written to
stdout, which is why the codebase forbids `System.out.println`.

Wire it into a client by pointing the client's MCP server configuration at that command. For
Cursor, add an entry to `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "aegis": {
      "command": "java",
      "args": ["-jar", "/absolute/path/aegis-mcp-gateway-0.1.0-all.jar"],
      "env": {
        "MCP_GW_TRANSPORT": "stdio",
        "MCP_GW_ADAPTERS": "flink",
        "FLINK_REST_URL": "http://localhost:8081"
      }
    }
  }
}
```

## 3. What you get on the first run

Writes are locked, so only `READ` class tools are registered. Ask the client to list tools and you
will see the read surface of whichever adapters you enabled, and nothing else. A withheld tool is
logged at startup with the reason it was withheld, so an empty tool list is always explainable.

Try `list_jobs` for Flink or `list_topics` for Kafka. Every call runs the same ten step chain and
appends an audit line.

## 4. First run over HTTP

The HTTP transport refuses to start without an inbound credential. The quickest credible setup is a
hashed token file.

```bash
TOKEN="$(openssl rand -hex 32)"
HASH="$(printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1)"
printf 'analyst:%s:*:*:true\n' "$HASH" > /etc/aegis/auth-tokens.txt
chmod 600 /etc/aegis/auth-tokens.txt

export MCP_GW_TRANSPORT=http
export MCP_GW_HTTP_HOST=127.0.0.1
export MCP_GW_HTTP_PORT=8090
export MCP_GW_AUTH_MODE=tokenfile
export MCP_GW_AUTH_TOKENS_FILE=/etc/aegis/auth-tokens.txt
java -jar mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

Then:

```bash
curl -s http://127.0.0.1:8090/healthz
curl -s -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -H 'Mcp-Method: tools/list' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
     http://127.0.0.1:8090/mcp
```

The MCP endpoint is `/mcp`. `/healthz`, `/readyz` and `/metrics` are unauthenticated on purpose so
an orchestrator can probe them; they expose no tool surface.

Enable TLS before binding anything other than loopback. See [operations.md](operations.md).

## 5. Unlocking a write

Writes need two independent things: the feature flag and an approval secret. Missing either one is
a startup error rather than a silent read-only mode.

```bash
export MCP_GW_WRITE_ENABLED=true
export MCP_GW_APPROVAL_SECRET="$(openssl rand -hex 32)"
```

Mint a token for one call:

```bash
java -cp mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar \
  io.github.vaquarkhan.aegis.core.governance.Approval \
  "$MCP_GW_APPROVAL_SECRET" cancel_job job-42 300
```

Pass the printed value as the `approvalToken` argument of the call. It is single use, bound to that
tool and scope, and expires after the TTL.

## 6. Configuration sources

Settings resolve lowest priority first: built-in defaults, then the `gateway.yaml` named by
`MCP_GW_CONFIG`, then environment variables. Secrets are environment only, so a mounted config file
cannot carry an approval secret or a keystore password.

```yaml
gateway:
  transport: http
  logLevel: INFO
http:
  host: 127.0.0.1
  port: 8090
auth:
  mode: tokenfile
  tokensFile: /etc/aegis/auth-tokens.txt
governance:
  rps: 25
  maxBytes: 65536
adapters:
  enabled:
    - flink
    - kafka
```

The full key list is in [LLD-apache-mcp-gateway.md](LLD-apache-mcp-gateway.md).

## Next steps

- [operations.md](operations.md) for TLS, OAuth, token files, approvals and the HA limits.
- [adapters.md](adapters.md) for the engines and their settings.
- [DESIGN-apache-mcp-gateway.md](DESIGN-apache-mcp-gateway.md) for why the chain is shaped this way.
