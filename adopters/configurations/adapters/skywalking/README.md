<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache SkyWalking adopter

`skywalking.yaml` governs three read-only SkyWalking OAP queries: service listing, service instances, and recent traces.

## Why this source

The SkyWalking OAP server exposes one GraphQL query endpoint (default port 12800, `POST /graphql`), so every tool posts to that single path with a fixed, reviewed GraphQL query and binds only typed variables (a time `duration` and a `service` id). No caller-supplied GraphQL text is spliced into the request, so a tool cannot be turned into an arbitrary query. The OAP GraphQL surface is a query API with no mutations, so every tool is READ.

## Run it

A retained two-door run lives under [`examples/adopters/skywalking/`](../../../../examples/adopters/skywalking/). Use official `apache/skywalking-oap-server:9.7.0` with `SW_STORAGE=h2`, wait until `POST /graphql` with `{ version }` returns HTTP 200, then:

```bash
export AEGIS_KNOX_HOME=/path/to/knox-3.0.0
./examples/adopters/run-example.sh skywalking --keep
```

`skywalking.yaml` is the profile the Keep loads. Its address is a placeholder hostname; the test substitutes the OAP address and egress CIDR.

## Evidence

`AdapterConfigsTest` loads this profile against a local contract harness. `SkyWalkingFrontDoorsTest` is the real-engine Keep through own-host and Knox.
