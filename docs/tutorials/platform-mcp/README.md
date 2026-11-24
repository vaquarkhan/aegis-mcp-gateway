<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# Platform MCP series: govern an AI agent across a streaming lakehouse

Three-part tutorial: **one Aegis MCP gateway** in front of real **Kafka, Flink, Iceberg, and Spark**.
Written and run by Vaquar Khan. Runnable code, scripts, and Terraform live in the companion repo
[aegis-platform-mcp-tutorial](https://github.com/vaquarkhan/aegis-platform-mcp-tutorial); the article
text and diagrams are mirrored here for the GitHub Pages tutorial site.

| Part | Article | Covers |
| --- | --- | --- |
| 1 | [Part 1: The incident, the problem, and standing it up](part1/DZONE-ARTICLE-PART1.md) | Incident story, MCP risk, local build, tokens, Cursor / Kiro / VS Code / Claude / Gemini, first allow and deny |
| 2 | [Part 2: Inside the governance, and the full proof](part2/DZONE-ARTICLE-PART2.md) | How each engine starts, `aegis.yaml` rule by rule, hard deny, dry-run, independent approval, audit verify |
| 3 | [Part 3: The same gateway on AWS](part3/DZONE-ARTICLE-PART3.md) | SSM-only EC2, Cognito JWT, S3 evidence, modular Terraform, teardown |

## How to run (not just read)

1. Build Aegis 2.0 from source (artifacts are not on Maven Central yet — see the publish guide in the PR / maintainer notes):

```powershell
git clone https://github.com/vaquarkhan/aegis-mcp-gateway.git
cd aegis-mcp-gateway
git checkout aegis-mcp-gateway-2.0-review-ready   # or main when it carries 2.0
mvn -B -Preal-engines -DskipTests install
$env:AEGIS_HOME = (Get-Location).Path
```

2. Clone and start the platform tutorial:

```powershell
git clone https://github.com/vaquarkhan/aegis-platform-mcp-tutorial.git
cd aegis-platform-mcp-tutorial
.\Start-All.ps1
.\Run-Walkthrough.ps1
```

MCP endpoint: `http://127.0.0.1:18080/mcp` · Tokens: `run/keys/tokens.json`

## Dataset and use case (short)

| Seed | Role in the story |
| --- | --- |
| Iceberg `sales` / medallion layers including gold; `finance.ledger` | Readers see sales/gold; finance is drop-protected |
| Kafka topic `orders` + Connect `orders-file-source` | Streaming orders an agent can inspect |
| One running Flink job | Something real to cancel only with approval |
| Spark SUBMITTED driver | Apps an operator can list |

**Use case:** stop an IDE agent with over-broad engine credentials from dropping finance tables, cancelling the wrong Flink job, or reading bronze when it is only a sales analyst — with every decision in the audit journal.
