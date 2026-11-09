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

There is no kept run for this profile. SkyWalking is new in 2.0 and had no Aegis 0.1 adapter. There is no `examples/adopters/skywalking/HOW-TO-RUN.md` and no `adopters/tutorials/skywalking/ide/` folder to copy.

`skywalking.yaml` is the profile the contract test loads. Its address is a placeholder hostname. Starting the gateway with this file does not start the product.

See [Future roadmap: remaining target verification](../../../../docs/ROADMAP.md#future-roadmap-remaining-target-verification).

## Evidence

`AdapterConfigsTest` loads this profile against a local contract harness. That is not a two-door run against the product.
