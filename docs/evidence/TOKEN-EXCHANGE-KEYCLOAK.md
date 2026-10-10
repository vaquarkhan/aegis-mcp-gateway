<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Evidence: per-caller backend credentials by RFC 8693 token exchange, against Keycloak 26.8.0

The script was run on 2026-10-03 and reportedly repeated on **2026-10-05** against Keycloak 26.8.0
`start-dev` and a gateway jar from that working tree:
[`examples/adopters/token-exchange/keycloak_e2e.py`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/token-exchange/keycloak_e2e.py).

These runs are historical `recorded-unattested` evidence. The five reported checks and environment
were recorded, but no immutable raw process output, commit/tree manifest, or content-addressed bundle
was retained. The Keycloak release archive hash value was not retained, so this record does not claim
that an archive hash can now be reproduced.

| Part | Version |
| --- | --- |
| Keycloak process | 26.8.0 release zip, `start-dev` on 127.0.0.1, JDK 21.0.12 |
| Aegis process | shaded gateway jar from the recorded working tree, started with `java -jar` on JDK 17 |
| Backend | Keycloak's own `userinfo` endpoint, which answers with the user of the presented token |

## Command

From `examples/adopters/token-exchange`, the retained script invocation is:

```text
python keycloak_e2e.py --keycloak http://127.0.0.1:18180 --admin-password-file pw.txt --jar ../../../aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar
```

If JDK 17 is not the default `java`, the script's `--java <path-to-jdk-17-java>` option selects the
Aegis runtime recorded above. The password file is local test input and is not retained in evidence.

## Setup the script creates through the Keycloak admin API

- `mcp-client`: the MCP client. Its user tokens carry `aud: aegis-gateway` and a `groups` claim.
- `aegis-gateway`: confidential, with `standard.token.exchange.enabled: true`, and an audience mapper
  for `orders-api`, which Keycloak requires before issuing tokens for that audience.
- `orders-api`: the backend audience. Users `alice` and `bob` are in group `analysts`.

The Aegis source uses `credentials: {type: token-exchange, audience: orders-api, scope: openid, ...}`
and the host verifies inbound JWTs against the realm's JWKS.

## Recorded output

```text
PASS the user token is issued for the gateway, not the backend: {'aud': ['aegis-gateway', 'account'], 'azp': 'mcp-client', 'groups': ['analysts']}
PASS alice's call reaches the backend with a token for alice: ('ALLOWED', None, '{"sub":"bf7c3d5b-...","preferred_username":"alice",...')
PASS bob's call reaches the backend with a token for bob: ('ALLOWED', None, '{"sub":"34196848-...","preferred_username":"bob",...')
PASS no token appears in the audit journal: 4 records
PASS with exchange disabled at Keycloak, the call is refused, with no fallback credential: ('DENIED:BACKEND_ERROR:step10', 'BACKEND_ERROR', 'Aegis denied this call: BACKEND_ERROR (step 10). backend credentials could not be resolved (TokenExchangeException)')
all checks passed
```

## What the historical run showed, and what it did not

- Each caller reportedly reached the backend as themselves: the same tool returned alice for alice and
  bob for bob.
- The exchange was on the path: when Keycloak stopped allowing the gateway client to exchange, the
  same call was refused with `BACKEND_ERROR` at step 10, with no shared-credential fallback.
- The historical run and its then-current unit test recorded that the HTTP executor sent the exchanged
  token rather than the inbound token. Keycloak's userinfo response alone is not proof of that fact.
- The run did not cover the Knox front door. Knox did not bind a verified inbound JWT for this provider,
  so a `token-exchange` source failed closed there.
- Other identity providers that implement RFC 8693 were not run.

Current token-exchange source and tests have changed since these live runs, including stricter handling
of readable JWT subjects and compact JWE. Those current behaviors are not attested by this historical
Keycloak record and require a new candidate-bound run.
