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
# ADR-001: an A2A agent joins Aegis as a tool source; no A2A front door

- Status: accepted for 2.0 (to be revisited by the community); part 2 implemented 2026-10-03
- Date: 2026-09-26, revised 2026-09-27 and 2026-10-03
- Deciders: the author; open for review with the rest of the 2.0 branch

## Context

The question was whether Aegis should also govern the Agent2Agent protocol (A2A), the Linux
Foundation project for agents that discover and delegate work to other agents
([specification](https://a2a-protocol.org/latest/specification/)). Aegis 2.0 governs MCP: an agent
calling tools, reading resources and fetching prompts on systems such as Spark, Flink and Kafka.

What the A2A specification defines, in summary (paraphrased from the specification):

- an **Agent Card** that describes an agent's identity, skills, endpoint and authentication;
- operations such as SendMessage, SendStreamingMessage, GetTask, ListTasks, CancelTask,
  SubscribeToTask and push-notification configuration, over JSON-RPC, gRPC or HTTP+JSON bindings;
- **stateful tasks** with a lifecycle (working, input required, completed, failed, canceled, rejected);
- **push notifications**: the remote agent POSTs task updates to a webhook URL the client supplies.

## Decision

Two parts, and the distinction matters more than the original version of this ADR admitted.

1. **Aegis 2.0 does not speak A2A on its front door.** There is no A2A endpoint, no Agent Card
   served, no task lifecycle, no push-notification webhook. The reasons are below.
2. **Calling another agent is a tool call, and that is already the governed shape.** A remote A2A
   agent can be exposed to the model as an ordinary MCP tool through a `ToolSource`, in which case
   every existing control applies to the delegation: classification, resource scoping, policy,
   egress, rate limits, approval, audit, output redaction and screening. That is the accepted
   direction for A2A support, and it needs no new engine.

The original ADR only stated part 1, which left the impression that A2A traffic simply cannot be
governed by Aegis. That was wrong in an important way: the interesting risk is not "Aegis does not
serve A2A", it is "a model delegates work to another agent and nobody recorded or bounded it". Part 2
is where that risk is actually addressed: delegated work becomes an ordinary governed tool action
with an explicit caller, target, bounded resource surface and audit record.

### Original design sketch, superseded by the implementation below

The following records the decision as it stood before implementation. It is not a description of the
current source:

- one source, `type: a2a`, per remote agent; **the sketch proposed** making its Agent Card skills tool
  definitions and pinning them like other definitions;
- **the sketch proposed** `MUTATE` as a per-skill default;
- **the sketch proposed** `SendMessage` with polling to completion and no subscriptions or webhooks;
- the agent would see a gateway credential; the gateway audit record would retain the inbound caller
  identity and any delegation chain that the own host or Knox had already established.

### Update 2026-10-03: part 2 is implemented, with four changes from the sketch above

The `a2a` source now exists ([`aegis-source-a2a`](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/aegis-2.0-review-20261009/aegis-source-a2a),
[profile](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/adopters/configurations/a2a/README.md)). Part 1 is unchanged: there is still no A2A
front door. Three points differ from the sketch, each for a reason found while building it:

- **One tool per agent, not one per skill.** An A2A 1.0 message has no field that selects a skill;
  the remote agent decides from the content. A per-skill tool would carry a classification the
  protocol cannot enforce, so the agent endpoint is the governed unit and the skills are listed in its
  description.
- **Unclassified means `DESTRUCTIVE`,** like every other source, rather than a special `MUTATE`
  default. The reference profile classifies its agent `MUTATE` explicitly.
- **Protocol scope:** discovery accepts advertised JSON-RPC Agent Card interfaces whose protocol version
  begins with `1.` and emits requests with `A2A-Version: 1.0`. The shipped evidence validates an A2A
  1.0 agent only; accepting a later 1.x card is not an interoperability claim until it has its own
  reviewed evidence.
- **No polling.** A call sends one `SendMessage` and waits for the reply within the timeout. A task
  that is not finished, or that asks for more input, is returned as a tool error; tasks are not
  continued across calls.

The credential sent to the agent is the source's own, or a per-caller token when the source uses
`credentials.type: token-exchange`. Evidence: `A2aToolSourceTest`, and `A2aAgentFrontDoorsTest`
against an agent built on the official `a2a-sdk` 1.2.1, through both front doors.

## Reasons for keeping the front door MCP-only

1. **A different unit of governance.** Aegis decides on a *tool call*: a named operation, typed
   arguments, typed resources, a READ / MUTATE / DESTRUCTIVE class, an exact argument digest that an
   approval is bound to. An A2A message is natural-language or structured content addressed to
   another agent, whose effect on real systems is decided by that agent, not visible to the
   gateway. Several controls that make Aegis useful (resource extraction, dry run, a receipt for the
   exact action) have little to bind to. Exposing the agent as a tool does not remove that limit; it
   makes it explicit, which is why an operator must classify the agent by its strongest possible effect.
   An unclassified A2A tool is `DESTRUCTIVE`; the reference profile explicitly classifies its inventory
   agent `MUTATE`, and both classes require approval.
2. **A different state model.** A2A tasks are long-lived, stateful objects with their own
   lifecycle. Aegis 2.0 is designed so that no governance state depends on protocol state (that is
   why it works for both the stateful 2025 MCP and the stateless 2026-07-28 MCP). Governing A2A
   properly means tracking task ownership and every status and artifact update, which is a
   different engine.
3. **A different direction of traffic.** Push notifications are server-initiated HTTP calls to a
   client-chosen URL. Aegis deliberately refuses server-to-client requests on MCP streams; accepting
   webhooks would reverse a core safety property and needs its own egress and SSRF design.
4. **Scope creep would weaken what exists.** A half-built A2A proxy would be a second, less tested
   policy surface next to a well-tested one. The review of 2.0 is easier, and the result safer, if
   the protocol surface stays exactly MCP.

## What still works today

- An agent that is itself an A2A server can call its tools **through** Aegis over MCP. The actions
  that touch real systems are then governed, whichever protocol brought the request to that agent.
- The inbound delegation chain is retained in Aegis's caller identity and audit record: RFC 8693 `act`
  claims at the own host and `ActorChainPrincipal` in Knox become `CallerIdentity.actorChain`, and
  delegated identities cannot approve. The A2A source does not add an A2A actor to that chain.
- W3C trace context joins an A2A source call to the Aegis audit record when the upstream client forwards
  `traceparent`.

## Future work on the A2A decision

- **A2A tool source (part 2):** implemented for one A2A 1.x JSON-RPC Agent Card interface using
  `SendMessage`, with evidence against an A2A 1.0 agent. Future work is an explicit interoperability
  policy and evidence for later 1.x versions, card-signature verification, other bindings, streaming,
  and task continuation. The implementation is documented in
  [adopters/configurations/a2a](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/adopters/configurations/a2a/README.md).
- **A2A front door (part 1):** requires A2A to define typed, side-effect-declaring skills so a message
  maps to a classifiable action, and a community decision to add a second protocol engine with its own
  tests rather than stretching this one.

## Consequences

- 2.0 has an A2A client (the `a2a` source, part 2) and no A2A server. This ADR should not be read as
  claiming that Aegis governs A2A traffic that does not pass through that source.
- The engine SPI (`PolicyDecisionPoint`, `AuditSink`, `StateStore`, `DecisionListener`) is
  protocol-neutral, so a future A2A front door could reuse policy, audit and state without
  changing the MCP path.
