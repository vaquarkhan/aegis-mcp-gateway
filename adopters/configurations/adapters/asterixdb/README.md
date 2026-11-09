<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache AsterixDB adopter

`asterixdb.yaml` governs four AsterixDB HTTP operations: server version, cluster state, a read-only SQL++ query, and a destructive controller shutdown.

## Why this source

AsterixDB publishes a stable HTTP API on its API port (default 19002), so this adopter uses declarative `http` tools. The query tool forwards a SQL++ statement, so it is classified READ only because the `sql-readonly` hook is bound to it; the shutdown tool is DESTRUCTIVE and follows the validate, approve, promote flow.

## Run it

There is no kept run for this profile. AsterixDB is new in 2.0 and had no Aegis 0.1 adapter. There is no `examples/adopters/asterixdb/HOW-TO-RUN.md` and no `adopters/tutorials/asterixdb/ide/` folder to copy.

`asterixdb.yaml` is the profile the contract test loads. Its address is a placeholder hostname. Starting the gateway with this file does not start the product.

See [Future roadmap: remaining target verification](../../../../docs/ROADMAP.md#future-roadmap-remaining-target-verification).

## Evidence

`AdapterConfigsTest` loads this profile against a local contract harness. That is not a two-door run against the product.
