<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache ActiveMQ adopter

`activemq.yaml` governs three ActiveMQ Classic 6.x Jolokia operations: broker status, queue discovery and
destructive queue removal.

## Why this source

ActiveMQ exposes these operations through the Jolokia bridge on its web console, so this is a declarative
`http` profile. ActiveMQ 6 ships Jolokia as POST only, with an Origin check and basic auth, so every tool
is a POST of a Jolokia JSON request and the gateway holds the broker credential
(`ACTIVEMQ_JOLOKIA_USER`, `ACTIVEMQ_JOLOKIA_PASSWORD`). The Broker MBean name is fixed by the operator;
callers supply only the typed `activemq:queue` resource.

The 0.1 `purge_queue` is not kept: purge lives on the queue's own MBean, and naming that MBean from an
argument would mean splicing the argument into a string. `activemq_remove_queue` (Broker MBean
`removeQueue`) takes the queue as a separate argument instead.

## Broker change the profile needs

The shipped `conf/jolokia-access.xml` refuses every `exec`. Add `<command>exec</command>` and remove
`<operation>removeQueue</operation>` from the Broker MBean deny entry. Keep the rest denied.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/activemq/activemq.yaml
```

Replace the base URL, Origin, CIDR, groups, signing key and brokerName for the deployment.

## Evidence

Verified against a stock ActiveMQ Classic 6.3.2 broker through both front doors:
[examples/adopters/activemq](../../../../examples/adopters/activemq/README.md). The route contract test in
`AdapterConfigsTest` still covers request rendering.
