<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Atlas adopter

`atlas.yaml` governs four Atlas v2 operations: type definition listing, entity search, entity detail and destructive entity deletion.

## Why this source

Atlas publishes a REST v2 surface, so this adopter uses declarative `http` tools. Entity identifiers become governed typed resources before deletion is considered.

## Run it

There is no kept run for this profile. There is no `examples/adopters/atlas/HOW-TO-RUN.md` and no `adopters/tutorials/atlas/ide/` folder to copy.

`atlas.yaml` is the profile the contract test loads. Its address is a placeholder hostname. Starting the gateway with this file does not start the product.

Atlas needs its own process plus Kafka, Solr, and HBase. This tree has no command that started that stack.

See [Future roadmap: remaining target verification](../../../../docs/ROADMAP.md#future-roadmap-remaining-target-verification).

## Evidence

`AdapterConfigsTest` loads this profile against a local contract harness. That is not a two-door run against the product.
