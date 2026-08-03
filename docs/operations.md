# Operations

Author: Viquar Khan.

Running Aegis in front of real infrastructure. This page covers TLS, the three inbound
authentication modes, the token file format, approval minting, observability, and the limits you
need to know before you scale out.

## Transport and TLS

The HTTP transport refuses to start without an inbound credential, and it warns loudly when it
binds a non-loopback address in plaintext. Terminate TLS at the gateway or immediately in front of
it.

| Key | Default | Meaning |
| --- | --- | --- |
| `MCP_GW_HTTP_HOST` | `127.0.0.1` | Bind address |
| `MCP_GW_HTTP_PORT` | `8090` | Bind port |
| `MCP_GW_HTTP_TLS_ENABLED` | `false` | Enable the TLS connector |
| `MCP_GW_HTTP_TLS_KEYSTORE` | none | Keystore path, required when TLS is enabled |
| `MCP_GW_HTTP_TLS_KEYSTORE_PASSWORD` | none | Keystore password, environment only |
| `MCP_GW_HTTP_TLS_KEYSTORE_TYPE` | `PKCS12` | Keystore type |

Create a PKCS12 keystore from an existing certificate and key:

```bash
openssl pkcs12 -export \
  -in server.crt -inkey server.key \
  -out /etc/aegis/server.p12 -name aegis \
  -passout pass:"$KEYSTORE_PASSWORD"
chmod 600 /etc/aegis/server.p12
```

Enabling TLS without a keystore or without a password is a startup error, not a downgrade to
plaintext.

### MCP hint headers

Streamable HTTP clients may send `Mcp-Method` and `Mcp-Name`. The gateway logs them at debug level
when present, which lets an operator or an edge proxy see which JSON-RPC method and tool a request
carries without logging the body and, with it, the tool arguments.

Set `MCP_GW_REQUIRE_MCP_HEADERS=true` to reject a POST to `/mcp` that carries no `Mcp-Method` with
400. Leave it off unless a proxy in front of the gateway depends on the header. The values are
hints and never decide routing or authorization; the request body remains the only source of truth.

## Inbound authentication

Pick one of three modes. All three fail closed.

### Shared bearer token

`MCP_GW_HTTP_BEARER_TOKEN` sets a single token shared by every caller. Comparison is constant time.
Use it for a single-consumer deployment or a smoke test; it gives you one identity in the audit log
and no way to revoke one caller without revoking all of them.

### Token file (recommended for most deployments)

`MCP_GW_AUTH_MODE=tokenfile` with `MCP_GW_AUTH_TOKENS_FILE` gives each caller its own token,
identity and allow lists. Tokens are stored as SHA-256 hashes, so a leaked file does not hand over
usable credentials, and lookup walks every entry so timing does not reveal which prefix matched.

Line format, colon separated, blank lines and `#` comments ignored:

```
callerId : sha256Hex(token) : jobsAllowCsv : jarsAllowCsv : readonly [: outboundAuthorizationHeader]
```

A shorter four or five field form is still accepted for older files, where one CSV list becomes the
resource scopes, the job allow list and the jar allow list at once:

```
callerId : sha256Hex(token) : resourceScopesCsv : readonly [: outboundAuthorizationHeader]
```

The two five-field shapes are told apart by field four: a boolean there means the older form.

Example:

```
# analyst can read anything, cannot write
analyst : 3f79...c1 : * : * : true

# ops-bot may act on two jobs and one jar, and may write
ops-bot : 9a12...4d : job-42,job-77 : etl-1.0.jar : false

# tenant-a carries its own outbound credential to the backend
tenant-a : b7e0...09 : * : * : true : Bearer eyJhbGciOi...
```

Rules worth knowing:

- `*` is the wildcard. An empty allow list denies everything, which is the intended direction for a
  misconfigured caller.
- A duplicate token hash is a startup error, as is a file with no entries.
- The last field, when present, is sent as the outbound `Authorization` header for that caller's
  backend calls. Keep the file mode at `600`.

Generate an entry:

```bash
TOKEN="$(openssl rand -hex 32)"
printf 'ops-bot:%s:job-42:etl-1.0.jar:false\n' \
  "$(printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1)" >> /etc/aegis/auth-tokens.txt
echo "give this to the caller once: $TOKEN"
```

Rotating a caller means replacing its line and restarting. The file is read at startup.

### OAuth 2.1 protected resource

`MCP_GW_AUTH_MODE=oauth` turns the gateway into an OAuth resource server that verifies bearer JWTs
against the issuer's JWKS.

| Key | Required | Meaning |
| --- | --- | --- |
| `MCP_GW_OAUTH_ISSUER` | yes | Expected `iss` claim |
| `MCP_GW_OAUTH_AUDIENCE` | yes | Value that must appear in `aud` |
| `MCP_GW_OAUTH_JWKS_URL` | yes | JWKS endpoint used for signature verification |
| `MCP_GW_OAUTH_REQUIRED_SCOPE` | no | Scope every token must carry |
| `MCP_GW_OAUTH_WRITE_SCOPE` | no | Scope that lifts read-only, default `aegis.write` |

What the filter checks, in order: the algorithm is in the RSA or ECDSA family, the signing key is
in the JWKS, the signature verifies, `iss` matches, `aud` contains the configured audience, `exp`
and `nbf` hold within 60 seconds of skew, and the required scope is present. Then `sub`, or
`preferred_username` when `sub` is absent, becomes the caller identity and the token scopes become
the caller's resource scopes.

Operational notes:

- Keys are cached by `kid` for five minutes. A token presenting an unknown `kid` triggers one
  refresh, so key rotation takes effect without a restart.
- A caller is read-only unless the token carries the write scope. A token minted for a broader
  audience therefore cannot unlock destructive tools by accident.
- HMAC signed tokens are refused outright, so a published JWKS key can never be reached as a shared
  secret.
- Without `MCP_GW_OAUTH_JWKS_URL` the gateway refuses to start on HTTP. Issuer and audience alone
  describe the tokens you would like to see but give the gateway no way to verify one.
- A token with no `scope` or `scp` claim yields an identity with no resource scopes, which reaches
  only tools that bind no resource. Map scopes in your authorization server rather than relying on
  a scope-less token.

### Modes that do not start

`MCP_GW_AUTH_MODE=cimd` and `MCP_GW_AUTH_MODE=spiffe` refuse to start an HTTP listener in 0.1.0.
The client identity metadata document verifier is implemented and tested, and will fetch a document
over HTTPS and require its `client_id` to equal the URL it came from, but naming a client is not
authenticating one, so it cannot gate traffic until the authorization server flow lands.

## Writes and approvals

Two independent settings unlock writes. Missing either is a startup error.

```bash
export MCP_GW_WRITE_ENABLED=true
export MCP_GW_APPROVAL_SECRET="$(openssl rand -hex 32)"
export MCP_GW_APPROVAL_TTL_MS=300000
```

With writes locked, `MUTATE` and `DESTRUCTIVE` tools are never registered, so a model does not even
see them. With writes unlocked they are registered but each call must carry an approval token.

Mint one:

```bash
java -cp mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar \
  io.github.vaquarkhan.aegis.core.governance.Approval \
  "$MCP_GW_APPROVAL_SECRET" <tool> [scope] [ttlSeconds]
```

For example `... Approval "$SECRET" cancel_job job-42 300`. The token is the only thing written to
stdout, so it can be captured with a shell substitution; usage and diagnostics go to stderr.

A token is HMAC-SHA256 signed and bound to the tool name and scope. It is single use, tracked by a
nonce, and expires after its TTL. A wildcard scope inside the token matches any required scope; the
reverse is not true, so a token minted for `job-42` cannot be replayed against `job-77`.

Operationally, mint approvals from a change management system or an on-call runbook step, not from
the same process that makes the call. The value of the token is that a human or a separate system
authorized this specific action.

## Governance settings worth tuning

| Key | Default | Effect |
| --- | --- | --- |
| `MCP_GW_RPS` | `5` | Per caller request rate |
| `MCP_GW_BREAKER_FAILURES` | `5` | Failures before a backend is cut off |
| `MCP_GW_BREAKER_RESET_MS` | `30000` | Half-open delay |
| `MCP_GW_TOOL_TIMEOUT_MS` | `30000` | Per call ceiling |
| `MCP_GW_MAX_BYTES` | `65536` | Output size bound |
| `MCP_GW_MAX_SQL_CHARS` | `32768` | SQL argument ceiling |
| `MCP_GW_DLP_ENABLED` | `true` | Redact secrets in tool output |
| `MCP_GW_EGRESS_ALLOW_HOSTS` | none | Outbound allow list |
| `MCP_GW_TOOLS_CATALOG` | none | Pinned tool schema digests |
| `MCP_GW_SHUTDOWN_TIMEOUT_MS` | `15000` | Drain budget |

The egress guard always refuses cloud instance metadata endpoints such as `169.254.169.254`, even
when the allow list is empty. Set the allow list explicitly anyway.

Pinning the tool catalog with `MCP_GW_TOOLS_CATALOG` is the defence against a swapped adapter
quietly changing what an agent may call. A digest mismatch is logged and the tool is refused.

## Observability

- `/healthz` is liveness. `/readyz` is readiness and starts failing as soon as shutdown begins.
- `/metrics` is Prometheus text format.
- Audit lines carry the caller id, the tool, the decision and, on a denial, a stable code from the
  deny taxonomy. Alert on `POLICY_DENIED` and `APPROVAL_REQUIRED` across every engine with one rule
  rather than per engine.
- Logs go to stderr through SLF4J and Logback. `MCP_GW_LOG_LEVEL` sets the level for the root and
  application loggers.
- No token, secret, keystore password or outbound header value is ever logged. If you see one,
  treat it as a security bug and follow [SECURITY.md](../SECURITY.md).

## Shutdown

The shutdown hook fails readiness probes first, then drains the backend executor, then stops the
listener, each bounded by `MCP_GW_SHUTDOWN_TIMEOUT_MS`. Give the orchestrator a termination grace
period longer than that value, otherwise in-flight calls are killed rather than drained.

## High availability limitations in 0.1.0

Three pieces of state live in the process and nowhere else:

- **Approval nonces.** Single use is enforced per replica. With N replicas an approval token can be
  replayed up to N times, once against each.
- **Rate limiter buckets.** `MCP_GW_RPS` is per replica, so the effective cluster limit is N times
  the configured value.
- **Circuit breaker state.** A backend that trips one replica stays available through the others,
  which slows recovery and can keep a struggling backend under load.

Until shared state lands, choose one of these:

1. Run a single replica and accept the availability trade-off. This is the honest default for a
   deployment where approvals matter.
2. Run multiple replicas with sticky routing per caller, which keeps rate limiting and breaker
   state coherent per caller but does not fix nonce replay.
3. Run multiple replicas with writes disabled (`MCP_GW_WRITE_ENABLED=false`). With no approvals in
   play, only the rate limit and breaker multiply, and both are then just capacity settings.

Divide `MCP_GW_RPS` by the replica count if you scale out, and size the breaker for one replica's
share of traffic.

Other stateful notes: the token registry, the tool catalog overlay and the policy file are read at
startup, so rotating any of them means a restart. The semantic cache and the daily token budget are
also per process.
