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
# How governance gets its information, and how future policy plugs in

Two questions that decide whether a gateway is worth adopting, and both of them are really the same
question asked from opposite ends.

1. **A governance or security team needs facts about MCP activity. Where do they come from, in what
   shape, and can they be trusted?**
2. **Policy will change. A new regulation, a new threat, a new ASF-wide rule. Can that be expressed
   without changing this tool?**

If the answer to the first is "parse the logs" and the answer to the second is "fork the engine", the
tool is a liability. What follows is what the code actually provides, with the seams named.

## Part 1: what a governance team gets

Four surfaces, three of them machine-readable and one of them designed to be read by a person.

### 1.1 The audit journal: decision and execution records, hash-chained

A pre-execution denial normally produces one `DECISION` record. A call promoted toward execution first
requires a durable `ALLOW_INTENT`, then normally produces an `EXECUTION_RESULT` or `DRY_RUN_RESULT`.
The intent and result are separate records, so a successful execution normally has two. A real denial
record, from the Flink tutorial run:

```json
{"seq":62,"event":"DECISION","ts":"2026-09-28T01:45:59.037868900Z",
 "gateway":"aegis-flink-tutorial","actor":"ana","effectiveSubject":"ana",
 "issuer":"aegis-static","authMethod":"static-token",
 "source":"flink","tool":"flink_get_job","toolClass":"READ",
 "outcome":"DENIED:INVALID_INPUT:step2","code":"INVALID_INPUT","step":2,
 "reason":"arguments do not match the tool schema: missing required property /jobId",
 "dryRun":false,"durationMs":0,"protocolVersion":"2026-07-28",
 "requestId":"req_ba46237f54f40b6190b69f91",
 "traceId":"cede6d6ba405b9145a46f2dd8d910b1b","spanId":"fd0edcdf8bd406ce",
 "prev":"sha256:769c253f...","hash":"sha256:ef9674ea..."}
```

Read the fields as the answers to a reviewer's questions:

| Question a reviewer asks | Field |
| --- | --- |
| Who did it? | `actor`, `effectiveSubject`, and the whole delegation chain when one exists |
| How were they authenticated, and by whom? | `authMethod`, `issuer` |
| What did they try to do? | `tool`, `toolClass`, `source` |
| To which object? | the typed resources, and an argument digest rather than the arguments |
| What happened? | `outcome`, `code`, `step`, `reason` |
| Was it a real attempt or a preview? | `dryRun` |
| When, and how long did it take? | `ts`, `durationMs` |
| Can I join this to the backend's own logs? | `traceId`, `spanId` (W3C, forwarded to the backend as `traceparent`) |
| Has anyone edited this? | `prev`, `hash`, `seq` |

Three properties matter more than the field list.

**It records each stage according to its role.** A pre-execution denial normally has one `DECISION`
record. A successful call requires an `ALLOW_INTENT` before the backend is contacted and normally adds a
terminal result record afterwards. The denial and terminal-result appends are best effort, so an outage
can leave either absent. The mandatory `ALLOW_INTENT` append is different: if it fails, execution is
blocked. Approval and receipt nonce operations may already have succeeded and remain consumed because
those operations and the journal append are not one transaction.

**Mandatory intent fails closed; observers do not become controls.** `AuditSink.append` must succeed for
`ALLOW_INTENT` before credentials or backend execution. A denial or terminal result has already been
determined, so failure to append that best-effort record is logged without changing the response.

**Tampering is detectable offline, with no server.** Each record chains to the previous one's hash:

```
$ java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --verify-audit audit.jsonl
OK records=62 head=sha256:ef9674eac16d647d63756c7a3e0a433a1a273c956f3291d04d58eeb4e9c2696d
```

Deleting or editing any line breaks the chain and the verifier says where. Scope: the chain
proves internal consistency. An attacker with write access to both the file and the host can rewrite the
whole chain consistently, which is why anchoring the head hash off-host is on the [roadmap](ROADMAP.md)
and is not claimed here.

**Arguments are digested, not stored, by default.** `audit.includeArguments` is `false` unless an
operator turns it on. A governance record should not become a second copy of the data, and a tool
argument can be a customer identifier.

### 1.2 Metrics: the same decisions, aggregated, safe to leave on a dashboard

`GET /metrics` in Prometheus text format, per tool, per source, per outcome:

```
aegis_decisions_total{kind="tool",method="flink_run_jar",source="flink",outcome="ALLOWED"} 4
aegis_decisions_total{kind="tool",method="flink_run_jar",source="flink",outcome="POLICY_DENIED"} 1
aegis_decisions_total{kind="tool",method="flink_run_jar",source="flink",outcome="PROMPT_INJECTION"} 1
aegis_decisions_total{kind="tool",method="flink_run_jar",source="flink",outcome="VRP_FAILED"} 1
aegis_decisions_total{kind="tool",method="flink_run_jar",source="flink",outcome="SCOPE_DENIED"} 1
```

One tool, five outcomes, four of them refusals. That is a security dashboard without writing a log
parser.

**No caller, resource or argument is ever a label**, deliberately. Labels become time series, and a
metric labelled by user identity is both a cardinality bomb and a disclosure. Identity questions belong
to the audit journal, which is access-controlled; aggregate questions belong to metrics, which usually
are not.

### 1.3 Traces: the call joined to what the backend then did

When `tracing.otlp.endpoint` is set, each decision is a span, and the gateway forwards a W3C
`traceparent` to the backend. A Flink JobManager's own logs then carry the same trace id, so "the agent
asked to stop job X" and "the JobManager stopped job X" are one trace rather than two systems and a
timestamp guess.

Status: the OTLP exporter is verified against a local HTTP test endpoint that asserts the document shape, not
against a live collector. That is a [roadmap](ROADMAP.md) item, and it is there because the
specification's hex id encoding differs from what several SDKs emit, so only a real collector settles it.

### 1.4 The admin API: the human-facing part

```
GET  /aegis/v1/approvals                  pending requests            (approvers)
GET  /aegis/v1/approvals/{id}             one request
POST /aegis/v1/approvals/{id}/approve     a decision                  (approvers, not the requester)
POST /aegis/v1/approvals/{id}/deny
GET  /aegis/v1/catalog                    every tool, with integrity status and digest (approvers)
POST /aegis/v1/catalog/{tool}/accept      adopt a quarantined definition (approvers)
GET  /aegis/v1/prompts                    the same for prompts
POST /aegis/v1/prompts/{prompt}/accept
```

`GET /catalog` is the one to notice. It answers "what can agents currently reach here, and has any of it
changed since it was approved?" A tool whose definition changed is quarantined rather than adopted, and
an approver has to accept the new digest. That is a governance question no log can answer after the
fact.

### 1.5 What a governance team does *not* have to do

- No log parsing. The audit record is the data structure.
- No agreement about vocabulary. The [deny codes](DENY-CODES.md) and step numbers are a fixed registry;
  a hook cannot invent a wire code.
- No per-engine work. The current deployable adapter inventory is 25 configurations and 116 tools,
  and all produce records in one shape because governance lives in the engine and adapters contribute
  capability only.
- No sampling decisions. There is no sampling.

## Part 2: how future policy plugs in

The design rule is one sentence: **the chain is fixed, the decisions inside it are pluggable.**

Ten numbered steps, always in the same order, so a denial is reproducible and an audit record means the
same thing next year. What each step consults is replaceable. That is the opposite of 0.1's design,
where interceptors carried a `priority()` and the chain could be reordered, which meant no two
deployments could be compared.

### The five seams

| Seam | Interface | Answers | Shipped implementations |
| --- | --- | --- | --- |
| **Policy** | `PolicyDecisionPoint` | may this caller do this, to this object? | built-in rule policy; OPA over HTTP |
| **Extension controls** | `GovernanceHook` | anything else, before or after any of the four operations | `sql-readonly`, `remote` (out-of-process HTTP) |
| **Audit** | `AuditSink` | where does the record go? | hash-chained file; in-memory |
| **Credentials** | `CredentialProvider` | what credential reaches this backend? | static per source; RFC 8693 token exchange for own-host JWT callers |
| **Capability** | `ToolSource` | what tools exist, and how are they called? | declarative HTTP; MCP proxy; plugin sources |

Two more exist and are less often needed: `StateStore` (approval nonces, rate counters) and
`DecisionListener` (observers that cannot change a decision).

### Seam 1: a new policy engine, without touching the engine

`PolicyDecisionPoint` is one method:

```java
Result decide(CallerIdentity caller, Action action, Map<String,Object> backendArguments) throws Exception;
```

`Action` carries the tool, its class, the **typed resources** and an argument digest, so a PDP can
answer per object rather than per tool name. Any exception is a denial.

They compose, and every one must allow:

```java
PolicyDecisionPoint pdp = RulePolicy.allOf(List.of(builtIn, opa, ranger));
```

So "the organisation's own rules" and "this deployment's rules" coexist without either having to know
about the other. Adding Apache Ranger, Casbin or a KIE rule set is one class implementing one method;
no engine change, no new deny code, no chain reordering. The
[client, backend or policy](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/adopters/frameworks/README.md#client-backend-or-policy) section of the
frameworks README describes where a policy engine fits relative to Aegis.

What this seam deliberately does not allow: a PDP cannot grant something the earlier steps refused. It
is consulted at step 3. Exposure and scope were decided at steps 1 and 2, and approvals happen at step
4. A policy engine can only narrow.

### Seam 2: a new control, without being Java

`GovernanceHook` runs for **all four governed operations**, not just tool calls, because
`resources/read`, `prompts/get` and `completion/complete` all put data or instructions into a model's
context. Each binding is an operator decision:

```yaml
hooks:
  - type: remote
    operations: [TOOL_CALL, RESOURCE_READ]
    mode: ENFORCE                  # or AUDIT, or OFF
    groups: [contractors]          # only for these callers
    sources: [iceberg]             # only for this backend
    settings:
      url: https://policy.internal.example/aegis/hook
      timeoutMs: 800
```

`type: remote` is the answer to "can we express our own rule without contributing Java to this project?"
The gateway POSTs a JSON document describing the call and reads an allow or deny. Any language.

The contract is what makes it safe to accept an outside opinion:

- Hooks run **after** the built-in steps 1 to 8 and **before** the dry-run receipt check and commit, so
  a hook cannot bypass a built-in control by running first.
- A hook may only deny with an existing registry code (`POLICY_DENIED`, `INVALID_INPUT`,
  `PROMPT_INJECTION`); anything else becomes `POLICY_DENIED`. The taxonomy stays reliable.
- A hook that throws, returns null or times out **denies** in `ENFORCE` mode. Fail-closed.
- `AUDIT` mode records the same event and continues, so a new rule can be measured on real traffic
  before it starts refusing.
- A hook must not mutate the context it is given.

`AUDIT` mode is the practical answer to how an organisation-wide rule can roll out: ship it as
advisory, review the audit journal for an agreed period, then change it to `ENFORCE` if the evidence
supports that decision.

### Seam 3: a new backend, without code

The deployable adapter inventory is 25 configurations and 116 tools. Twenty-two configurations are
HTTP-only; Calcite and Hive use `jdbc`; ZooKeeper combines HTTP AdminServer commands with the
`zookeeper` client-protocol source. Additional typed sources such as `kafka-admin` and
`spark-connect` are plugins outside that 25-folder adapter inventory. `AdapterConfigsTest` drives every
configured tool. A backend using another protocol is a `ToolSource` discovered by `ServiceLoader`.

### Seam 4: a new audit destination

`AuditSink.append(Map)` and a durability contract. A Knox deployment can forward records to Knox's own
audit service alongside the file journal. A regulated deployment can write to a WORM store. The engine
does not care. Failure of the mandatory `ALLOW_INTENT` append blocks execution; denial and terminal
result appends are best effort after the response outcome is determined.

### Seam 5: credentials

`CredentialProvider` resolves the backend credential **per target, after all pre-execution controls and
durable `ALLOW_INTENT` succeed**. `CallerIdentity` has no credential field. A static provider returns the
gateway service credential. With own-host `credentials.type: token-exchange`, the verified inbound JWT
travels only to the configured, egress-checked token endpoint to obtain a backend-audience token; the
inbound token itself never travels to the protected backend, audit journal, hook payload, or policy input.
Post-execution output hooks and output controls necessarily run after backend execution.

## Part 3: what cannot be plugged in, and why that is the point

Extensibility that has no limits is not a design. Four things are deliberately fixed:

**The order of the ten steps.** Extensions get one slot. If a deployment could reorder the chain, no two
Aegis deployments would mean the same thing, and an audit record would not be comparable across
projects. The [earliest control wins](DENY-CODES.md), and that is a property people can rely on.

**The deny code registry.** A hook cannot invent a wire code. A dashboard built on `POLICY_DENIED` keeps
working when a deployment adds a control.

**Enforcement controls fail closed.** A configured PDP, enforcement hook, parser, or mandatory
`ALLOW_INTENT` append that cannot complete does not permit execution. Advisory `AUDIT` hooks and
`DecisionListener` observers are intentionally isolated: their failures are logged and cannot deny,
grant, or otherwise alter the enforcement result.

**An adapter cannot contribute policy.** Adapters provide tool definitions, resources, egress hosts and
backend calls. The moment an adapter could relax a control, every adapter becomes security-critical and
the current 25-configuration inventory could not be reviewed as capability-only data.

## Part 4: worked example, end to end

Suppose a new rule arrives: *no agent may read a dataset classified "restricted" outside a change
window, and every such attempt must be reported.*

None of that is in Aegis, and none of it needs to be.

1. **Where does the classification live?** Your catalog, presumably Apache Atlas or Ranger. Aegis does
   not need to know.
2. **Write a hook**, in any language, reachable over HTTP. It receives the caller, the operation, and
   the typed resources such as `iceberg:table:finance.ledger`. It asks the catalog for the
   classification, checks the clock against the change window, and answers allow or deny.
3. **Bind it advisory first:**
   ```yaml
   hooks:
     - {type: remote, mode: AUDIT, operations: [TOOL_CALL, RESOURCE_READ], settings: {url: https://classify.internal/hook}}
   ```
4. **Measure.** Every advisory denial or failure is in the audit journal's `hookAudit` field with the
   hook's name and reason. `aegis_decisions_total` counts only the final governance outcome and has no
   hook label, so measure advisory-hit frequency from the audit journal, not the decision counter.
5. **Enforce.** Change `AUDIT` to `ENFORCE`. Denials become `POLICY_DENIED` at the hook's step, with the
   hook's reason in the record, and the reporting requirement is already met because the audit journal was
   always recording it.

No engine change. No new deny code. No adapter change. No fork. The rule is in the organisation's
system, where it belongs, and the gateway is the place it gets applied.

## Related reading

- [DENY-CODES.md](DENY-CODES.md): the ten steps and the code registry
- [PLUG-AND-PLAY.md](PLUG-AND-PLAY.md): the extension points as an operator sees them
- [CONFIGURATION.md](CONFIGURATION.md): `hooks`, `policy`, `audit`, `tracing`, `metrics`
- [GOVERNANCE-MAPPING.md](GOVERNANCE-MAPPING.md): the controls against OWASP Agentic and the MCP
  specification
- [ARCHITECTURE.md](ARCHITECTURE.md): the module boundaries that keep the engine host-neutral
- [ROADMAP.md](ROADMAP.md): the seams that exist with no second implementation yet
