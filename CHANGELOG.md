# Changelog

Author: Viquar Khan.

## 0.1.0

- Multi-module Aegis MCP Governance Gateway (Java 17)
- Core interceptor chain (10 steps), auth, FinOps, catalog integrity, YAML manifests
- Flink adapter (REST + SQL Gateway) with offline HTTP-fake tests
- Kafka adapter with AdminClient, Schema Registry HTTP, and DLQ inspect
- Spark adapter with History Server, Livy submit/kill, optional SQL HTTP
- Iceberg adapter with REST catalog reads/mutates, credential redaction, VRP dry-run
- OAuth resource server with JWKS JWT validation (Nimbus)
- Dist shade jar, Docker, Helm charts
- Docs (`getting-started`, `operations`, `adapters`, DESIGN, LLD, RESEARCH) and examples
