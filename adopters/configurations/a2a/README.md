<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# A2A agent profile

[`a2a.yaml`](a2a.yaml) offers one Agent2Agent (A2A) protocol 1.0 agent to MCP clients as one governed
tool, through the `a2a` source in [`aegis-source-a2a`](../../../aegis-source-a2a). Aegis is an A2A client
here. It does not offer an A2A endpoint of its own.

## What a call does

1. At startup and on catalog refresh, Aegis reads the agent card (`settings.cardUrl`) and builds one
   tool. The tool description lists the agent's skills and the endpoint calls go to.
2. A call runs the full governance chain (identity, policy, classification, approval, egress, audit),
   then sends one JSON-RPC `SendMessage` with the `text` argument as a text part and the optional
   `data` argument as a data part. The `A2A-Version: 1.0` header and the source credential are sent.
3. The agent's reply becomes the tool result: a message's parts, or a completed task's artifacts and
   status message. A task that fails, is rejected or canceled, or asks for more input becomes a tool
   error. Task history is never forwarded.

## Decisions you make

| Setting | Why it matters |
| --- | --- |
| `tools.<name>.class` | An A2A message cannot select a skill, so classify the agent by the strongest thing it can do. Unclassified, it is `DESTRUCTIVE`. |
| `egress.allowHosts` | The agent's host must be listed. A plugin source's host is not added for you. |
| `credentials` | Sent on calls only. The agent card is read without credentials, as A2A expects a public card. |
| `settings.endpointUrl` | Needed only when the card names an endpoint on another origin than the card. It pins that exact URL. |

## Limits

- A2A 1.0 over JSON-RPC only. A 0.3 card, gRPC or HTTP+JSON bindings are refused at startup with a
  message that names what the card offered.
- One message per call. Aegis does not continue a task (`taskId`/`contextId`) and does not stream.
- Card signatures (`signatures` in the card) are not verified. The defences are the same-origin rule,
  the definition digest (a changed card quarantines the tool) and the egress allow list.
- There is no native preview. A dry run is the governance preflight; nothing is sent to the agent.

## Evidence

[`A2aAgentFrontDoorsTest`](../../../examples/aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/A2aAgentFrontDoorsTest.java)
runs this file against an agent built on the official `a2a-sdk` 1.2.1
([`examples/adopters/a2a`](../../../examples/adopters/a2a/README.md)), through the own host and inside
Apache Knox 3.0.0.
