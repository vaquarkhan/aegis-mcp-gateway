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
# aegis-source-a2a

Agent-to-Agent (A2A) source plugin (`type: a2a`). One A2A 1.0 agent becomes one governed tool over
JSON-RPC `SendMessage`, with a same-origin rule for the agent card endpoint.

## What it does

The module reads one agent's A2A card and offers the agent as a single MCP tool. A call sends one
JSON-RPC `SendMessage` with the caller's `text`, and optional `data`, and turns the reply into tool
text. An A2A message cannot pick a skill, so the agent endpoint is the governed unit and the operator
classifies it by the strongest thing the agent can do. Aegis acts only as an A2A client here. The
engine decides whether a call may run; this module only runs it.

## How it works end to end

1. **Discovery.** `src/main/resources/META-INF/services/io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory`
   registers `A2aToolSourceFactory`, whose `type()` returns `a2a`. For a source with `type: a2a`,
   `GatewayFactory` finds the factory through `ServiceLoader`. The factory implements only
   `create(id, settings)`, so the default `BuildContext` overload delegates to it.
2. **Settings.** The factory reads the keys below. `GatewayFactory` calls `ConfigNode.finish()`, so any
   other key under `settings:` fails startup.

   | Key | Default | Rule |
   | --- | --- | --- |
   | `cardUrl` | required | Absolute `http` or `https` URL with a host and no user-info. |
   | `endpointUrl` | none | Same URL rules; pins the JSON-RPC URL the card must name. |
   | `toolName` | source id with `-` as `_` | 1 to 64 of letters, digits, `_`, `.`, `-`. |
   | `maxTextChars` | `16000` | 1 to 1000000. |
   | `maxResponseBytes` | `1048576` | 1024 to 16777216. |
   | `cardTimeoutMs` | `5000` | 100 to 60000. |

   This module reads no environment variable. The agent credential goes in the source's
   `credentials:` section; the engine resolves it and passes it as `ExecutionContext.credentials()`
   headers on calls only.
3. **Card.** `A2aToolSource.discover()` sends a `GET` to `cardUrl` with only `Accept: application/json`,
   no credentials, redirects never followed, at most 256 KiB and `cardTimeoutMs` in total
   (`BoundedHttp.send`). A status other than 200, or a body that is not a JSON object, fails discovery.
   The card needs a `name` and a `supportedInterfaces` entry with `protocolBinding` `JSONRPC` and a
   `protocolVersion` starting with `1.`; the first such entry is used, with its optional `tenant`.
4. **Endpoint origin rule.** The interface `url` must be an absolute `http(s)` URL without user-info.
   With `endpointUrl` set, it must match that URL (scheme, host, port, path ignoring trailing `/`,
   query), and the pin is used. Otherwise it must be on the card's own origin (scheme, host, port).
5. **Tool.** One tool named `toolName`, titled with the card `name`. Its input schema has `text`
   (required, 1 to `maxTextChars`) and `data` (object, optional), with `additionalProperties: false`.
   The description lists the agent description, its skills and "Calls go to <endpoint>", capped at
   4000 characters. Class, resources and dry-run mode come from the source's `tools:` section; an
   unclassified tool is `DESTRUCTIVE`.
6. **Engine first.** `egressTargets` returns the endpoint, so the egress control checks it before every
   call. The engine calls `ToolExecutor.execute` only when all controls pass.
7. **SendMessage.** The executor re-checks `text` (non-empty, at most `maxTextChars` code points) and
   `data` (an object). It builds `{"jsonrpc":"2.0","id":<requestId>,"method":"SendMessage","params":{...}}`
   as a JSON value with a `message` of `role` `ROLE_USER`, a random `messageId` and parts
   `[{"text": text}, {"data": data}]`; arguments are never spliced into a string. It POSTs to the
   endpoint fixed at discovery with `Content-Type` and `Accept` `application/json`, `A2A-Version: 1.0`,
   the credential headers and `traceparent`. The deadline is `ExecutionContext.timeoutMillis()` and the
   reply is read up to `maxResponseBytes`.
8. **Results.** `render` maps the reply to text. A JSON-RPC `error` is a tool error with its code and
   message. A `message` result returns its parts. A `task` result collects artifact parts and the status
   message: `TASK_STATE_COMPLETED` is success, anything else is a tool error. Text parts are passed
   through, data parts are written as JSON, `url` parts become `[file at URL]`, `raw` parts become
   `[binary part omitted]`. Task `history` is never read.

Minimal source, adapted from [adopters/configurations/a2a/a2a.yaml](../adopters/configurations/a2a/a2a.yaml)
(run against an `a2a-sdk` agent by `A2aAgentFrontDoorsTest`):

```yaml
egress:
  allowHosts: [inventory-agent.internal.example]
  schemes: [https]

sources:
  - id: inventory-agent
    type: a2a
    timeoutMs: 30000
    credentials: {type: bearer, tokenEnv: A2A_AGENT_TOKEN}
    settings:
      cardUrl: https://inventory-agent.internal.example/.well-known/agent-card.json
      maxTextChars: 4000
    tools:
      inventory_agent:
        class: MUTATE
```

```text
startup / refresh:
  GET cardUrl (no credentials, no redirects, 256 KiB, cardTimeoutMs)
  -> first JSONRPC 1.x interface -> endpoint on card origin, or equal to endpointUrl
  -> one tool, description includes skills and endpoint

MCP call -> engine controls (egress checks the endpoint) -> allowed
  -> dryRun? tool error, nothing sent
  -> check text and data
  -> POST endpoint: JSON-RPC SendMessage, A2A-Version: 1.0, credential headers, traceparent
       (deadline timeoutMillis, body at most maxResponseBytes)
  -> render(): message parts | completed task artifacts | tool error
  -> ToolResult (text) -> engine -> MCP client
```

## Tools

| Tool | Class | What it does |
| --- | --- | --- |
| `toolName` (default: source id with `-` as `_`, for example `inventory_agent`) | `A2aToolSource` (executor `A2aToolSource.Executor`) | Sends one `SendMessage` to the agent and returns its reply as text. |

## Failure behaviour

- Discovery throws `IOException` when the card times out, is not HTTP 200, is not a JSON object, has
  no `name`, offers no A2A 1.x JSON-RPC interface (the message lists what it found), or names an
  endpoint that breaks the origin or pin rule.
- A dry run returns a tool error "This agent offers no preview; nothing was sent." and sends nothing.
- Bad `text` or `data` is a tool error and nothing is sent.
- A timeout raises `ExecutionException` flagged as a timeout. Any other transport error, including a
  reply larger than `maxResponseBytes`, raises `ExecutionException` naming only the exception class.
  An interrupt raises `ExecutionException` "interrupted".
- HTTP 5xx or 429 raises `ExecutionException`, which counts toward the circuit breaker. Any other
  status that is not 200 is a tool error "Agent returned HTTP N".
- A reply that is not JSON, or has neither `result` nor `error`, or neither `message` nor `task`, is
  a tool error.
- `TASK_STATE_FAILED`, `TASK_STATE_REJECTED` and `TASK_STATE_CANCELED` are tool errors;
  `TASK_STATE_INPUT_REQUIRED` and `TASK_STATE_AUTH_REQUIRED` are tool errors saying Aegis does not
  continue a task; any other unfinished state is a tool error.

## Limits

- A2A 1.0 over JSON-RPC only: 0.3 cards and gRPC or HTTP+JSON bindings are refused.
- One `SendMessage` per call: no streaming and no task continuation.
- No native preview: a dry run never reaches the agent.
- Card signatures are not verified.
- The card read in `discover()` is not one of the `egressTargets`; only the JSON-RPC endpoint is
  checked by the egress control.
- One tool per agent; a skill cannot be governed on its own.

## Tests

- `src/test/java/io/github/vaquarkhan/aegis/source/a2a/A2aToolSourceTest.java` uses a local JDK
  `HttpServer` with fixed A2A 1.0 JSON. It covers the tool built from the card, the request shape and
  headers, task state mapping (with history not forwarded), JSON-RPC errors, 5xx and 404, dry run and
  bad arguments sending nothing, the origin and pin rules, refusal of 0.3 cards, gRPC bindings,
  non-http endpoints and malformed cards, settings validation (including an unknown key), odd parts in
  `render`, and a gateway build where a `MUTATE` agent call is denied with `APPROVAL_REQUIRED`, and a
  call with no allow-listed host is not allowed; in both cases nothing reaches the agent.
- `examples/aegis-validation/.../A2aAgentFrontDoorsTest.java` (profile `real-engines`) runs `a2a.yaml`
  against an agent built on the official `a2a-sdk` 1.2.1 through both front doors.

## Developer instructions

```powershell
mvn -B -pl aegis-source-a2a -am test
```

1. The JSON-RPC endpoint is the call's egress target and must pass the egress allow list; the
   same-origin and pin rules are fail-closed.
2. Profile example: [`adopters/configurations/a2a`](../adopters/configurations/a2a/).
3. The opt-in front-door test uses the official `a2a-sdk` agent under `examples/adopters/a2a/`. Without `AEGIS_A2A_PYTHON` that test is skipped, and a skip is not a retained pass.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-source-a2a)
- [A2A configuration README](../adopters/configurations/a2a/README.md)
- [AGENTS.md](../AGENTS.md)
