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

## What it does

This module decides who an HTTP request on the gateway's own listener comes from, and whether that
caller may reach the MCP, admin or metrics route. It parses the `host` configuration section into
`OwnServerSecuritySettings`, which builds exactly one `IdentityResolver`: JWT, static tokens or SPIFFE.
`OwnServerPerimeter` uses those settings to admit a request or answer 401 or 403 with the right
challenge, and to evaluate CORS preflight against exact origins. `ProtectedResourceMetadata` builds
the RFC 9728 discovery document and the `resource_metadata` challenge. The module has no HTTP server
and no governance logic: an admitted caller still goes through every engine control.

## How it works end to end

1. **Parse once at start-up.** `OwnServerSecuritySettings.parse(section, baseDir, env, clock)` reads
   `listen`, `tls`, `identity`, `oauth`, `routes`, `cors`, `stdio` and `metrics` through `ConfigNode`,
   then `finish()` refuses any key it did not read. Problems are reported as `ConfigException`, so
   the host does not start.
2. **Pick the identity.** `identity.type` defaults to `jwt`; `static` and `spiffe` are the other
   values, and anything else (including `anonymous`) is refused.
   - `jwt` builds a `JwtVerifier` from `issuer` and `audience` (required), `algorithms` (default
     `[RS256]`), `hmacSecretEnv`, `jwksUri`, `groupsClaim` (`groups`), `subjectClaim` (`sub`),
     `clockSkewSeconds` (60), `jwksTtlSeconds` (3600) and `jwksRefreshCooldownSeconds` (30). HS256
     needs a secret of at least 32 bytes; RS256 and ES256 need `jwksUri`.
   - `static` builds a `StaticTokenIdentity` from `tokens[]`: `tokenEnv` (the value must be at least
     16 characters and only its SHA-256 is kept) or `sha256`, plus `actor` and `groups`.
   - `spiffe` builds a `SpiffeIdentity` from `trustDomain` and `ids[]` (`id`, `actor`, `groups`; at
     least one, no duplicates). It needs `tls.clientAuth: require`, which needs `tls.keystore` and an
     existing `tls.clientCaFile`, so only certificates the handshake verified reach the resolver.
3. **Authenticate a request.** The host passes a header-only `McpHttpRequest` (headers, empty body,
   the verified client chain) to `OwnServerPerimeter.admit`, which calls
   `IdentityResolver.authenticate`. Bearer types share `IdentityResolver.bearer`, which needs exactly
   one `Authorization` header with the `Bearer` scheme (case-insensitive) and a non-empty token.

   ```text
   JwtVerifier.verifyWithScopes(token)
     size <= 16 KiB -> three segments -> header JSON (16 KiB, depth 8)
     -> alg in the allow list ("none" never is) -> no "crit" header
     -> signature: HS256 HMAC compared with MessageDigest.isEqual,
                   RS256 / ES256 with the JWKS key for kid and alg
     -> iss equals issuer -> aud equals, or is an array containing, audience
     -> exp present, now <= exp + skew -> nbf and iat not in the future (with skew)
     -> subject claim present -> groups claim (array, or space-separated string)
     -> act chain to depth 8: actor = first act.sub, effectiveSubject = sub
     -> scopes from "scope"; from "scp" only when "scope" is absent (never merged)

   StaticTokenIdentity.resolve(request)
     SHA-256 of the token compared with every entry by Digests.constantTimeEquals (no early exit)

   SpiffeIdentity.resolve(request)
     request.peerCertificates() empty -> null
     leaf not a CA; key usage, when present, has digitalSignature and not keyCertSign
     exactly one URI SAN: spiffe://<trustDomain>/<path>, no user-info, port, query or fragment,
     path segments of [A-Za-z0-9.-_] and none empty, "." or ".."
     ID looked up in ids[]; a valid but unlisted ID -> null
   ```

   JWKS keys are fetched with `BoundedHttp` (256 KiB, 5 seconds, no redirects). Keys without `kid`,
   with a `use` other than `sig`, RSA keys under 2048 bits and EC keys other than P-256 are skipped.
   An unknown `kid` or an expired cache triggers at most one refresh per cool-down. When the cached
   set has expired and cannot be refreshed, RS256 and ES256 tokens are rejected. `JwtVerifier` logs
   rejection reasons at debug level and `SpiffeIdentity` at info; neither is sent to the client.
4. **Groups and scopes.** Groups come from the JWT groups claim, or from configuration for static,
   SPIFFE and stdio callers. Only `JwtVerifier` carries scopes (`carriesScopes()` is true there and
   false by default). Each route has groups (`routes.mcpGroups`, `routes.adminGroups`,
   `metrics.allowedGroups`; empty means any authenticated caller) and optional scopes
   (`routes.mcpScopes`, `routes.adminScopes`, `metrics.requiredScopes`). Required scopes with a
   resolver that carries none stop start-up, as does a scope missing from a non-empty
   `oauth.scopesSupported`.
5. **Admit the route.** `admit` runs before the host reads any body:

   ```text
   OwnServerPerimeter.admit(route, headerOnlyRequest)
     authenticate() returned null
         -> 401 "authentication required", WWW-Authenticate: challenge(route)
     caller.scopes() does not contain every scope required for the route
         -> 403 "token lacks a required scope", WWW-Authenticate: insufficientScopeChallenge(route)
     route groups not empty and caller in none of them
         -> 403 "caller is not allowed to reach this route"
     otherwise
         -> allowed, Admission.caller() is the CallerIdentity
   ```

   Scopes are exact and case sensitive. A scope never replaces group membership: both must pass.
6. **Challenge headers.** Scope values are checked at parse time as RFC 6749 scope tokens (no space,
   quote or backslash), so they cannot break out of the quoted value.

   | Case | `WWW-Authenticate` |
   | --- | --- |
   | 401, no `host.oauth` | `Bearer realm="aegis"` |
   | 401, with `host.oauth` | `Bearer realm="aegis", resource_metadata="<metadata URL>"` |
   | 401, route requires scopes | either of the above plus `, scope="<scopes>"` |
   | 403, missing scope | `Bearer realm="aegis", error="insufficient_scope", scope="<scopes>"`, plus `resource_metadata` with `host.oauth` |
   | 403, outside route groups | none |

7. **CORS.** CORS is off unless `cors.allowedOrigins` lists exact origins (`*` is refused). Methods
   are upper-cased, header names lower-cased; when origins are set and the lists are empty they
   default to `POST, DELETE, GET` and the MCP headers (`authorization`, `content-type`,
   `mcp-protocol-version`, `mcp-method`, `mcp-name`). `maxAgeSeconds` is 0 to 86400 (default 600);
   `allowCredentials` needs at least one origin.

   ```text
   OwnServerPerimeter.preflight(origin, requestedMethod, requestedHeaders)
     CORS off, origin not listed, method missing or not listed,
     or any requested header (trimmed, lower-cased) not listed   -> rejected
     otherwise -> Access-Control-Allow-Origin: <origin>, Access-Control-Allow-Methods,
                  Access-Control-Allow-Headers, Access-Control-Max-Age, Vary: Origin,
                  Access-Control-Allow-Credentials: true only with allowCredentials
   OwnServerPerimeter.responseCorsHeaders(origin)
     allowed origin -> Access-Control-Allow-Origin, Vary: Origin, and when configured
                       Access-Control-Allow-Credentials and Access-Control-Expose-Headers
     anything else  -> no headers
   ```

   Preflight never authenticates and never changes route admission.
8. **OAuth protected resource metadata.** Off unless `oauth.resource` is set; the other `oauth` keys
   without it are refused. `resource` must be absolute without query or fragment, and https (http only
   for `localhost`, `127.0.0.1` or `::1`); `authorizationServers` (at least one) and `documentation`
   follow the same scheme rule. With `identity.type: jwt`, `resource` must equal
   `identity.jwt.audience` unless `allowAudienceMismatch: true`. `metadataPath()` is `/.well-known/oauth-protected-resource` plus the
   resource path without a trailing slash; `json()` holds `resource`, `authorization_servers`,
   `scopes_supported` (when not empty), `bearer_methods_supported: ["header"]` and
   `resource_documentation` (when set). Aegis is a resource server and never issues tokens.
9. **What the caller carries.** An admitted request yields a `CallerIdentity` with no raw token or
   claim material:

   | Field | `jwt` | `static` | `spiffe` | `stdio` |
   | --- | --- | --- | --- | --- |
   | `actor` | first `act.sub`, else subject | configured `actor` | mapped `actor` | `host.stdio.actor` |
   | `effectiveSubject` | subject claim | `actor` | `actor` | `actor` |
   | `groups` | groups claim | configured | configured | configured |
   | `issuer` | `identity.jwt.issuer` | `aegis-static` | `spiffe://<trustDomain>` | `aegis-stdio` |
   | `authMethod` | `jwt:<alg>` | `static-token` | `x509-svid` | `stdio` |
   | `actorChain` | `act` chain | empty | empty | empty |
   | `scopes` | `scope` or `scp` | empty | empty | empty |

   Static, SPIFFE and stdio callers carry no OAuth scopes, so a tool with `actionScopes` is denied
   to them by `GovernanceEngine` (`SCOPE_DENIED`). The stdio identity is built here but used by
   `StdioHost` directly; stdio requests never pass through `OwnServerPerimeter`.

## Main types

| Type | Role |
| --- | --- |
| `IdentityResolver` | Interface: `resolve`, `authenticate` (returns `Authenticated(caller, scopes)`), `carriesScopes`, `bearer` |
| `JwtVerifier` (`Settings`) | JWT verification with HS256, or RS256 and ES256 through a cached JWKS |
| `StaticTokenIdentity` (`Entry`) | SHA-256 token digests mapped to fixed identities |
| `SpiffeIdentity` | X.509-SVID from the verified TLS client chain, mapped by SPIFFE ID |
| `OwnServerSecuritySettings` (`CorsPolicy`) | Strict `host` parsing, challenge strings, per-route required scopes |
| `OwnServerPerimeter` (`RouteGroup`, `Admission`, `Preflight`) | Route admission, CORS preflight and response headers |
| `ProtectedResourceMetadata` | RFC 9728 document, metadata path and URL, 401 challenge |

## Failure behaviour

- Any invalid or unknown `host` key, an unset `*Env` secret, an HS256 secret under 32 bytes, RS256 or
  ES256 without `jwksUri`, or an empty token or SPIFFE list throws `ConfigException`.
- `identity.type: spiffe` without mutual TLS, `clientAuth` other than `none` or `require`, a
  missing `clientCaFile` or one set without `require`, and a metrics path that is relative or equals
  `mcpPath` or `adminPath` are refused.
- A credential that fails any check makes the resolver return `null` and the perimeter answer 401;
  the client only sees `authentication required`.
- An expired JWKS cache that cannot be refreshed rejects every RS256 and ES256 token; a JWKS fetch
  is not repeated inside the cool-down.
- A preflight that misses on origin, method or any requested header is rejected (the host sends 403).

## Tests

- `JwtVerifierTest`: claims mapped to the caller with immutable scopes; exact `iss`, `aud` and
  subject; skew on `exp`, `nbf` and `iat`; `alg: none`, an unconfigured algorithm, `crit`, a bad
  signature and malformed tokens; delegation and space-separated groups; ES256 through JWKS, weak RSA
  keys ignored, no refetch inside the cool-down, and an expired key set failing closed.
- `SpiffeIdentityTest`: only a mapped SVID in the trust domain authenticates; SPIFFE ID shape rules;
  `host` settings that require mutual TLS and an existing trust bundle.
- `OwnServerPerimeterTest`: admission by identity and route group with no body; exact-origin CORS and
  header normalization; wildcard origins and unknown keys refused.
- `OwnServerSecuritySettingsTest`: static tokens resolved by digest only; insecure or ambiguous
  settings refused; OAuth metadata bound to the JWT audience unless explicitly escaped.
- `ScopeEnforcementTest`: a token with the scope is admitted (`scope` and `scp` forms); a valid token
  without it gets the exact 403 step-up challenge; the 401 names the scope; scope and group are both
  required; misconfigured scopes stop start-up.
- `ProtectedResourceMetadataTest`: metadata path, URL and document; invalid resource and issuer
  values refused; published URLs are https or loopback only.
- Over real HTTP and TLS, `aegis-gateway` covers this module in `GatewayHostTest`,
  `ProtectedResourceMetadataTest` and `SpiffeMutualTlsTest`.

## Developer instructions

```powershell
mvn -B -pl aegis-mcp-security -am test
```

1. Authentication failures must be 401; never fall open to anonymous MCP.
2. Keep JWT algorithm allow lists and audience/issuer checks fail-closed.
3. Route and scope checks run before the host reads the request body: `OwnServerPerimeter.admit`
   takes a header-only request.
4. Document host-key changes in [`docs/CONFIGURATION.md`](../docs/CONFIGURATION.md).

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-mcp-security)
- [Security guide](../docs/guide/security.html)
- [AGENTS.md](../AGENTS.md)
