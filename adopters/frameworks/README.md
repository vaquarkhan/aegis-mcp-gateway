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
# Agent frameworks in front of a governed gateway

Examples where the **agent framework** is the client and Aegis is the boundary it reaches through. For
the other direction, governing a framework's own HTTP surface, see
[`../configurations/adapters/burr/burr.yaml`](../configurations/adapters/burr/burr.yaml).

The division these examples exist to make concrete:

```
The framework decides WHAT to try, in WHICH ORDER, and WHEN TO STOP.
Aegis decides WHETHER this caller may do it, on THIS object, RIGHT NOW, and records the answer.
```

An in-process framework cannot enforce the second column against itself. The rules would live in the same
process as the model output trying to get around them.

| Example | Framework | What it drives | Needs |
| --- | --- | --- | --- |
| [`burr/aegis_burr_agent.py`](burr/aegis_burr_agent.py) | Apache Burr (incubating) | the Flink tutorial gateway: read the cluster, stop a job through validate, approve, promote, then confirm the real outcome | `pip install burr` (optional) and the gateway from [TUTORIAL-FLINK.md](../../docs/TUTORIAL-FLINK.md) |

## Running the Burr example

```bash
# 1. bring up the Flink tutorial cluster and gateway (docs/TUTORIAL-FLINK.md parts 4.2 to 4.5)
# 2. submit a job so there is something to act on, or let the tutorial's run_jar step do it
# 3. then
pip install burr                       # optional: without it the same steps run in sequence
export OPERATOR_TOKEN='operator-token-0123456789'
export APPROVER_TOKEN='approver-token-0123456789'
export AEGIS_SAVEPOINT_DIR='file:///tmp/aegis-savepoints'
python burr/aegis_burr_agent.py
```

On Windows, put the variables in a `.cmd` file rather than using `set VAR=value && python ...`: that form
includes the space before the `&&` in the value, Flink then tries to create a savepoint directory whose
name ends in a space, and the failure reads like a permissions problem. This example calls `.strip()` on
the value for that reason, and the story is written up in
[TUTORIAL-FLINK.md](../../docs/TUTORIAL-FLINK.md) part 5.5.

## What a real run looks like

Against a real Apache Flink 1.20.1 cluster:

```
running the graph as an Apache Burr application

==============================================================================
Burr agent report: one incident, governed end to end
==============================================================================
  triage: 1 running job(s), cluster says {"taskmanagers":1,"slots-total":4,...
  decide: stop job 599e7a9e1a242d1e59d31eedf85d9b4d with a final savepoint
  attempt: refused APPROVAL_REQUIRED at step 4
  request: dry run ALLOWED, approval request apr_14b8865a... opened, receipt issued=True
  wait: the agent approving its own request -> HTTP 403; a human approver -> HTTP 200
  promote: approval alone -> VRP_FAILED at step 9
  promote: approval and receipt -> ALLOWED, {"request-id":"8a2b29ba84b5e16c062a805ca04a82bd"}
  confirm: status COMPLETED, operation says the savepoint succeeded
  confirm: the job is now FINISHED

Refused along the way, each with the gateway's own code and step:
  flink_stop_job   APPROVAL_REQUIRED    step 4    this DESTRUCTIVE call needs a per-call approval
  flink_stop_job   VRP_FAILED           step 9    an approval without a dry-run receipt

Outcome: the job was stopped and the savepoint is at file:/.../savepoint-599e7a-6be098d502ec
```

The refusals are the point. An agent that is never refused has not been governed, it has been trusted.

The `confirm` step is the second point. Aegis answering `ALLOWED` means governance permitted the call and
the backend accepted it. Whether the backend then *succeeded* is a third question, and Flink's savepoint
status reports `{"status":{"id":"COMPLETED"}}` for a savepoint that failed, with the truth in `operation`.
The example reads `operation` and re-checks the job state instead of trusting the word allowed.

## Adding another framework

Anything that can POST JSON is an MCP client: the 2026-07-28 protocol has no handshake and no session. The
client helper in `aegis_burr_agent.py` is about twenty lines of standard library, deliberately, and
[`../clients/`](../clients/) has the same thing in Python, Node and Rust. There is no Aegis SDK to depend
on and there is not going to be one.

Useful examples have three properties:

1. The framework does the deciding. If the example hard-codes the sequence, it is a shell script.
2. At least one refusal is shown, with the gateway's own code and step.
3. The final claim is verified against the backend, not against the gateway's answer.

Where a project fits relative to Aegis is described in [Client, backend or policy](#client-backend-or-policy)
below.

## Client, backend or policy

A project can relate to Aegis in one of three ways:

| Relationship | What it means | Can Aegis govern it? |
| --- | --- | --- |
| **Client** | the project is the agent, or hosts it, and calls tools | No. Aegis governs what the client may reach, not the client itself |
| **Backend** | the project has an HTTP control plane that can be placed behind a governed surface | Yes, with a configuration file |
| **Policy** | the project decides authorisation, so it belongs inside the chain, not behind it | Yes, through the `PolicyDecisionPoint` SPI |

Apache Burr is a client. It is an in-process Python library, so there is no network boundary for a
gateway to sit on, and a framework that granted permissions would be granting them to itself. The
boundary Aegis governs is the outward one: everything the Burr application reaches. The Burr tracking
server is also a backend, governed by
[`../configurations/adapters/burr/burr.yaml`](../configurations/adapters/burr/burr.yaml).

Keeping enforcement outside the agent process keeps the decision independent of the model output it
governs. If the framework enforced the rules, they would run in the same process as that output.

A policy engine such as Casbin or a KIE rule set fits as a `PolicyDecisionPoint` implementation rather
than as an adapter. None is built; see
[GOVERNANCE-INFORMATION-AND-EXTENSIBILITY.md](../../docs/GOVERNANCE-INFORMATION-AND-EXTENSIBILITY.md)
for the seam.

## Why MCP governance and not just an API key

An API key answers "is this client allowed in". An agent call needs a narrower answer: whether this
call, with these arguments, on this object, by this person, is allowed now. That answer also has to be
recorded where a reviewer can read it later.

- **The caller is not the client.** A Burr application runs on behalf of whoever is using it. One
  credential per application merges every user into one identity, and the audit trail can then only
  say that the agent made the call.
- **The arguments carry the risk.** `flink_run_jar` with an approved jar is routine; with an unreviewed
  jar it is remote code execution. Transport-layer authorisation cannot see the difference because the
  difference is in the request body.
- **Tools are discovered, so the surface is not fixed.** A model reads `tools/list` and decides what to
  call. A URL allow list cannot express "this caller may see these eleven tools and not those six".
- **Refusals have to be enforced and recorded.** The run above shows two refusals with stable codes
  and one rejected self-approval. In each case the agent would otherwise have proceeded.
