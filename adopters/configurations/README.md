<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Aegis 2.0 reference configurations

Every YAML in this directory lives in a named folder with a developer README. There are two groups:

```text
configurations/
  a2a/                   one A2A 1.0 agent as one governed tool
  existing-mcp-server/   generic profile for a project that already has MCP
  flink/                 reference Flink JobManager profile
  spark/                 reference Spark History/standalone profile
  spark-connect/         typed Spark Connect source profile
  iceberg/               reference Iceberg REST catalog profile
  kafka/                 Kafka Connect, Schema Registry and Kafka Admin profile
  template/              teaching template, not a deployment profile
  adapters/              25 project-specific adopter profiles
```

## Choose a configuration

| Directory | Executable configuration | Use it when | Source type | Evidence |
| --- | --- | --- | --- | --- |
| [a2a](a2a/README.md) | [a2a.yaml](a2a/a2a.yaml) | Offering an A2A agent to MCP clients | typed `a2a` | opt-in `A2aAgentFrontDoorsTest`; no retained results in this tree |
| [existing-mcp-server](existing-mcp-server/README.md) | [existing-mcp-server.yaml](existing-mcp-server/existing-mcp-server.yaml) | The project already exposes MCP over HTTP or stdio | `mcp` | protocol/governance test harness |
| [flink](flink/README.md) | [flink.yaml](flink/flink.yaml) | Governing Flink JobManager operations | `http` | current local Flink MiniCluster run |
| [spark](spark/README.md) | [spark.yaml](spark/spark.yaml) | Governing Spark UI/History reads and standalone submission actions | `http` | current local Spark driver-UI read run; standalone writes need separate evidence |
| [spark-connect](spark-connect/README.md) | [spark-connect.yaml](spark-connect/spark-connect.yaml) | Governing Spark Connect SQL/dataframe actions | typed `spark-connect` | current local Spark Connect server run |
| [iceberg](iceberg/README.md) | [iceberg.yaml](iceberg/iceberg.yaml) | Governing an Iceberg REST Catalog | `http` | target catalog endpoint required |
| [kafka](kafka/README.md) | [kafka.yaml](kafka/kafka.yaml) | Governing Kafka Connect, Schema Registry and Kafka Admin | `http` + typed `kafka-admin` | broker and Connect endpoints required |
| [template](template/README.md) | [TEMPLATE.yaml](template/TEMPLATE.yaml) | Starting a new project profile | `http` example | replace every placeholder before use |
| [adapters](adapters/README.md) | catalog of 25 named YAML profiles | Selecting a project-specific reference profile | HTTP, JDBC or typed source | evidence differs per adopter |

If you use Cursor or Kiro, the profile YAML is not what the editor loads. Open that profile's README,
start the engine and Aegis so the gateway stays listening, then copy `adopters/tutorials/<project>/ide/cursor/mcp.json`
to `.cursor/mcp.json` or `ide/kiro/mcp.json` to `.kiro/settings/mcp.json`. You can instruct the agent
after the editor lists the `aegis-<project>` server and its tools.

## Layout contract

A shared profile directory contains one same-named executable YAML and one README. `TEMPLATE.yaml` is
the only uppercase filename and lives only in `template/`. The test locator rejects flat YAML files at
this directory root, missing READMEs and unexpected profile directories.

The `adapters/` catalog is a separate inventory. Do not add generic shared profiles there, and do not
add project-specific YAML profiles at this root.
