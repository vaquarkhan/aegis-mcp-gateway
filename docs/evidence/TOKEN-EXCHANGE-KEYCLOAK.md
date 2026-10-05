<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Evidence: per-caller backend credentials by RFC 8693 token exchange, against Keycloak 26.8.0

Run on 2026-10-03 and repeated on **2026-10-05** (same script, Keycloak 26.8.0
`start-dev`, gateway jar from this tree) with
[`examples/adopters/token-exchange/keycloak_e2e.py`](../../examples/adopters/token-exchange/keycloak_e2e.py).

| Part | Version |
| --- | --- |
| Keycloak | 26.8.0, release zip (SHA-1 checked), `start-dev` on 127.0.0.1, JDK 21.0.12 |
| Aegis | the shaded gateway jar from this tree, started with `java -jar` on JDK 17 |
| Backend | Keycloak's own `userinfo` endpoint, which answers with the user of the presented token |

## Setup the script creates through the Keycloak admin API

- `mcp-client`: the MCP client. Its user tokens carry `aud: aegis-gateway` and a `groups` claim.
- `aegis-gateway`: confidential, with `standard.token.exchange.enabled: true`, and an audience mapper
  for `orders-api`, which is what Keycloak needs before it will issue tokens for that audience.
- `orders-api`: the backend audience. Users `alice` and `bob` in group `analysts`.

The Aegis source uses `credentials: {type: token-exchange, audience: orders-api, scope: openid, ...}`
and the host verifies inbound JWTs against the realm's JWKS.

## Output

```text
PASS the user token is issued for the gateway, not the backend: {'aud': ['aegis-gateway', 'account'], 'azp': 'mcp-client', 'groups': ['analysts']}
PASS alice's call reaches the backend with a token for alice: ('ALLOWED', None, '{"sub":"bf7c3d5b-...","preferred_username":"alice",...')
PASS bob's call reaches the backend with a token for bob: ('ALLOWED', None, '{"sub":"34196848-...","preferred_username":"bob",...')
PASS no token appears in the audit journal: 4 records
PASS with exchange disabled at Keycloak, the call is refused, with no fallback credential: ('DENIED:BACKEND_ERROR:step10', 'BACKEND_ERROR', 'Aegis denied this call: BACKEND_ERROR (step 10). backend credentials could not be resolved (TokenExchangeException)')
all checks passed
```

## What this shows, and what it does not

- Each caller reaches the backend as themselves: the same tool returns alice for alice and bob for bob.
- The exchange is on the path: when Keycloak stops allowing the gateway client to exchange, the
  same call is refused with `BACKEND_ERROR` at step 10, and nothing falls back to a shared credential.
- The inbound token is not forwarded: the http executor sends only the headers the credential
  provider returns (`TokenExchangeCredentialsTest` checks the backend receives the exchanged token and
  never the inbound one). Keycloak's userinfo would also accept the inbound token, so the userinfo
  reply alone is not that proof; the unit test and the disabled-exchange refusal are.
- Not covered: the Knox front door. Inside Knox, Aegis does not see a verified inbound JWT, so a
  `token-exchange` source refuses every call there. Other identity providers that implement RFC 8693
  were not run.
