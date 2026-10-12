<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Kafka and Kafka Connect profile

`kafka.yaml` combines three real surfaces: Kafka Connect REST, Schema Registry HTTP and the typed Kafka
Admin client source.

## Use it from Cursor or Kiro

The jar command further down does not connect an editor. `kafka.yaml` has no listener, and its broker
address is a hostname. Follow this order. The agent is on Aegis only after the last step.

1. Build, start Kafka and Connect, then start Aegis and leave it listening, as
   [docs/tutorials/kafka.md](../../../docs/tutorials/kafka.md) sections 2, 4, and 5 describe. Section 3
   of that tutorial is a test: it stops the gateway and `-Keep` overwrites the retained evidence.
2. Copy into the project the editor has open:
   - Cursor: [`adopters/tutorials/kafka/ide/cursor/mcp.json`](../../tutorials/kafka/ide/cursor/mcp.json) → `.cursor/mcp.json`
   - Kiro: [`adopters/tutorials/kafka/ide/kiro/mcp.json`](../../tutorials/kafka/ide/kiro/mcp.json) → `.kiro/settings/mcp.json`
3. Use the same secret for `AEGIS_VALIDATION_TOKEN_READER` and for `REPLACE_WITH_YOUR_TOKEN`. That token
   is the person `guest`. Leave the Knox entry in the Kiro file disabled unless you started Knox.
4. Reload MCP. You are ready when the gateway log still says `Aegis gateway listening on http://127.0.0.1:8080/mcp`
   and the editor lists `aegis-kafka` with its tools.
5. In the agent chat, name `aegis-kafka` and use a prompt from section 8 of the tutorial. The agent calls
   Aegis. It does not call the broker or the Connect URL.

The kept run does not start Schema Registry. Details of the copy are in
[`adopters/tutorials/kafka/ide/README.md`](../../tutorials/kafka/ide/README.md).

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

An independent, runnable Kafka MCP server with a signed server catalog and a break-glass drill
against a real engine is in [examples/break-glass-mcp-servers/kafka](../../../examples/break-glass-mcp-servers/kafka/README.md).
