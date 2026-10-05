<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Kafka and Kafka Connect profile

`kafka.yaml` combines three real surfaces: Kafka Connect REST, Schema Registry HTTP and the typed Kafka
Admin client source.

## Why this source

Kafka broker administration is not a REST facade, so topic operations use the typed `kafka-admin` source.
Kafka Connect and Schema Registry use HTTP tools. Each surface remains governed by one Aegis profile.

## Run

Use the gateway assembly containing the Kafka Admin client source:

```powershell
java -jar examples/aegis-gateway-kafka/target/aegis-gateway-kafka-2.0.0-SNAPSHOT-all.jar `
  --config adopters/configurations/kafka/kafka.yaml
```

Replace broker bootstrap servers, Connect and Schema Registry endpoints, credentials, groups, protected
topic policy, egress CIDRs and audit location.

## Evidence

`RealKafkaTest` runs when local `AEGIS_KAFKA_BOOTSTRAP` and `AEGIS_KAFKA_CONNECT_URL` endpoints are
provided. It skips when those services are absent. DLQ inspection and several Admin writes remain
separate roadmap work.
