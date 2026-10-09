<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Per-caller backend credentials with Keycloak (RFC 8693)

[`keycloak_e2e.py`](keycloak_e2e.py) configures a fresh realm in a running Keycloak 26.2 or newer,
starts the gateway jar with a `token-exchange` source, calls the tool as two users over MCP, and then
turns exchange off to show the call is refused. Python standard library only. The kept output is in
[docs/evidence/TOKEN-EXCHANGE-KEYCLOAK.md](../../../docs/evidence/TOKEN-EXCHANGE-KEYCLOAK.md).

```powershell
# Keycloak needs JDK 21
$env:JAVA_HOME = 'C:\jdk-21'
$env:KC_BOOTSTRAP_ADMIN_USERNAME = 'admin'; $env:KC_BOOTSTRAP_ADMIN_PASSWORD = '<choose one>'
keycloak-26.8.0\bin\kc.bat start-dev --http-port 18180 --http-host 127.0.0.1
# in another terminal, with the admin password in a file
python keycloak_e2e.py --keycloak http://127.0.0.1:18180 --admin-password-file pw.txt `
  --jar ..\..\..\aegis-gateway\target\aegis-gateway-2.0.0-SNAPSHOT-all.jar
```

The script deletes its realm when it finishes.

## The source configuration it uses

```yaml
credentials:
  type: token-exchange
  tokenEndpoint: https://idp.example.com/realms/acme/protocol/openid-connect/token
  clientIdEnv: AEGIS_TX_CLIENT_ID        # the gateway's own confidential client
  clientSecretEnv: AEGIS_TX_CLIENT_SECRET
  audience: orders-api                   # or resource: https://orders.example.com/
  scope: openid                          # optional
  # checkSubject: true                   # default: an issued JWT must name the caller's sub
  # discovery: {type: bearer, tokenEnv: ORDERS_DISCOVERY_TOKEN}   # tools/list has no caller
```
