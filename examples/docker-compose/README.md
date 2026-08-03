# Docker Compose

Author: Viquar Khan.

Requires the shade jar on the host.

```bash
mvn -q -pl mcp-gateway-dist -am package -DskipTests
docker compose up
```

Health: `http://localhost:8090/healthz`.

Auth uses the demo token file from `examples/02_http_tokenfile/tokens.example.txt`.
Present `Authorization: Bearer test` (plaintext whose SHA-256 is in that file). Do not reuse that
token outside local demos.
