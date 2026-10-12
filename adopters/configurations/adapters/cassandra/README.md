<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Cassandra adopter

`cassandra.yaml` governs four Cassandra Sidecar operations: keyspace listing, table listing, token-range read and destructive table drop.

## Why this source

The selected Cassandra Sidecar surface is HTTP, so the adopter is declarative `http` configuration. Aegis applies policy and approval around the typed keyspace/table target.

## Run it

There is no kept run for this profile. There is no `examples/adopters/cassandra/HOW-TO-RUN.md` and no `adopters/tutorials/cassandra/ide/` folder to copy.

`cassandra.yaml` is the profile the contract test loads. Its address is a placeholder hostname. Starting the gateway with this file does not start the product.

The paths in `cassandra.yaml` are not the Cassandra Sidecar API. Pointing Aegis at a Sidecar and treating a 404 as success would be a fake run.

See [Future roadmap: remaining target verification](../../../../docs/ROADMAP.md#future-roadmap-remaining-target-verification).

## Evidence

`AdapterConfigsTest` loads this profile against a local contract harness. That is not a two-door run against the product.
