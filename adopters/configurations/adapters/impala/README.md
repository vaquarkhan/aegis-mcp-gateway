<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Impala adopter

`impala.yaml` governs four Impala coordinator reads: status, metrics, query listing and query cancellation.

## Why this source

The selected coordinator surface is HTTP, so the profile is declarative. Query identifiers become Aegis resources before a cancellation action is considered.

## Run it

There is no kept run for this profile. There is no `examples/adopters/impala/HOW-TO-RUN.md` and no `adopters/tutorials/impala/ide/` folder to copy.

`impala.yaml` is the profile the contract test loads. Its address is a placeholder hostname. Starting the gateway with this file does not start the product.

The profile calls an Impala coordinator debug UI. This tree has no coordinator process that was started.

See [Future roadmap: remaining target verification](../../../../docs/ROADMAP.md#future-roadmap-remaining-target-verification).

## Evidence

`AdapterConfigsTest` loads this profile against a local contract harness. That is not a two-door run against the product.
