<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Paimon adopter

`paimon.yaml` governs four Paimon REST catalog operations: database/table discovery, table detail and destructive table deletion.

## Why this source

Paimon's catalog surface is HTTP, so a declarative profile can define the operations while Aegis enforces typed database/table policy and destructive approval controls.

## Run it

There is no kept run for this profile. There is no `examples/adopters/paimon/HOW-TO-RUN.md` and no `adopters/tutorials/paimon/ide/` folder to copy.

`paimon.yaml` is the profile the contract test loads. Its address is a placeholder hostname. Starting the gateway with this file does not start the product.

The published Paimon REST API is not the paths in `paimon.yaml`, and Apache does not ship a production REST catalog server for this profile.

See [Future roadmap: remaining target verification](../../../../docs/ROADMAP.md#future-roadmap-remaining-target-verification).

## Evidence

`AdapterConfigsTest` loads this profile against a local contract harness. That is not a two-door run against the product.
